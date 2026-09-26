package dev.forgesworn.cambium.unlock

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.databinding.ActivityUnlockEnrolBinding
import dev.forgesworn.cambium.pairing.Pairing
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.service.HeartwoodKeepAliveService
import dev.forgesworn.cambium.signer.RelayWatch
import dev.forgesworn.cambium.signer.UnlockNostr
import dev.forgesworn.cambium.signer.displayLabel
import dev.forgesworn.cambium.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rust.nostr.sdk.Event
import java.security.SecureRandom
import javax.crypto.Cipher

/**
 * Makes this phone an unlock phone for the board behind one paired identity.
 *
 * The primary path (enrol-invite spec v1) reverses the optical step: Sapwood shows an invite QR,
 * this phone scans it.
 *
 * 1. "Scan Sapwood's code" scans and strictly parses an [InviteUri]. A bunker link or another
 *    phone's own enrolment code gets a helpful wrong-direction message ([EnrolInviteScan]).
 * 2. Cambium then builds its [EnrolmentCode] exactly as before (a one-off enrolment pubkey, a
 *    one-off rendezvous tag, this phone's relays), seals it to the invite's `inv` key
 *    ([InviteReplyBuilder]) and publishes the reply once to the invite's relays. Sending/sent/
 *    failed shows on screen, with a Retry that republishes the exact same signed event.
 * 3. From there it is exactly the old flow: waits for the board's hand-off (now on its own relays
 *    plus the invite's relays), shows the five request words, then the check code, then the
 *    fingerprint that seals the slot secret under a new Keystore key ([SlotSecretVault]). Only
 *    then is the enrolment stored, the listener started, and the keep-alive switched on, so the
 *    phone hears the board after a power cut.
 *
 * "Show a code instead" keeps the original direction (this phone shows a QR, Sapwood scans it) as
 * a secondary option, for a Sapwood with no camera-visible screen or an older build.
 *
 * Nothing secret is ever on screen or passes through Sapwood. If the owner leaves before the
 * fingerprint, or waits more than [PENDING_TIMEOUT_MILLIS], the secret is dropped and the screen
 * says which board record to revoke.
 *
 * The hand-off is not signed by anything the phone already trusts, so anyone who saw the code (or
 * the invite) could race in an answer of their own. Three guards: the owner compares the five
 * request words between this phone and Sapwood (or the board's own card) before confirming, the
 * board shows the same five words before its button press, and a second, different answer arriving
 * before the fingerprint blocks the enrolment outright.
 * Declared with `configChanges` in the manifest so a rotation does not lose the enrolment key.
 */
class UnlockEnrolActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockEnrolBinding
    private lateinit var pairing: Pairing
    private lateinit var relays: List<String>
    private var enrolSecretHex: String? = null
    private var watch: RelayWatch? = null
    private var pending: HandOff? = null
    private var keyAlias: String? = null
    private var stored = false
    private var storedId = -1L
    /** Two different answers for one code: someone else has the code. Nothing is kept. */
    private var conflicted = false
    @Volatile private var destroyed = false

    /** Held for Retry: the exact signed event, and the invite's relays it targets. */
    private var pendingInviteReply: Event? = null
    private var pendingInviteRelays: List<String> = emptyList()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val scanLauncher = registerForActivityResult(ScanContract()) { result -> onInviteScanResult(result) }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner() else binding.enrolStatus.text = getString(R.string.enrol_camera_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnlockEnrolBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.enrolDoneButton.setOnClickListener { finish() }
        binding.enrolConfirmButton.setOnClickListener { promptSeal() }
        binding.enrolBatteryButton.setOnClickListener { requestBatteryExemption() }
        binding.enrolScanButton.setOnClickListener { onScanClicked() }
        binding.enrolShowCodeButton.setOnClickListener { onShowCodeClicked() }
        binding.enrolRetryButton.setOnClickListener { publishInviteReply() }

        val signer = intent.getStringExtra(EXTRA_SIGNER_PUBKEY)
        pairing = PairingStore(this).pairings().firstOrNull { it.signerPubkeyHex == signer } ?: return finish()
        binding.enrolTitle.text = getString(R.string.enrol_title, shortLabel(pairing))

        if (!SlotSecretVault.canUse(this)) {
            showOnly(getString(R.string.enrol_needs_biometric))
            return
        }
        relays = pairing.relays.map { it.trimEnd('/') }.filter(::isRelayUrl).distinct()
        if (relays.isEmpty()) {
            showOnly(getString(R.string.enrol_no_relays))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        destroyed = true
        val closing = watch
        watch = null
        // lifecycleScope is already cancelled here; the stop itself is NonCancellable native work.
        if (closing != null) CoroutineScope(Dispatchers.IO).launch { closing.stop() }
        if (!stored) {
            pending?.wipe()
            keyAlias?.let(SlotSecretVault::delete)
        }
        enrolSecretHex = null
        super.onDestroy()
    }

    // -- Secondary path: this phone shows a QR, Sapwood scans it (unchanged from before this spec). --

    private fun onShowCodeClicked() {
        binding.enrolScanButton.isVisible = false
        binding.enrolShowCodeButton.isVisible = false
        binding.enrolBody.isVisible = false
        binding.enrolShowCodeBody.isVisible = true

        val code = buildOwnEnrolment()
        val text = code.encode()
        binding.enrolCode.text = text
        binding.enrolCode.isVisible = true
        binding.enrolWords.text = requestWords(code.enrolPubkeyHex).orEmpty()
        binding.enrolWords.isVisible = true
        binding.enrolWordsHint.isVisible = true
        binding.enrolQr.setImageBitmap(BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 720, 720))
        binding.enrolQr.isVisible = true
        binding.enrolCopyButton.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Heartwood unlock enrolment", text))
        }
        binding.enrolCopyButton.isVisible = true
        binding.enrolStatus.text = getString(R.string.enrol_waiting)

        startHandOffWatch(relays, code.rendezvous)
    }

    // -- Primary path: Sapwood shows an invite, this phone scans it (enrol-invite spec v1). --

    private fun onScanClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.qr_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        scanLauncher.launch(options)
    }

    private fun onInviteScanResult(result: ScanIntentResult) {
        when (val evaluated = EnrolInviteScan.evaluate(result.contents)) {
            is EnrolInviteScanResult.Accepted -> onInviteAccepted(evaluated.invite)
            is EnrolInviteScanResult.Rejected -> binding.enrolStatus.text = evaluated.message
            EnrolInviteScanResult.Cancelled -> Unit
        }
    }

    private fun onInviteAccepted(invite: InviteUri) {
        binding.enrolScanButton.isVisible = false
        binding.enrolShowCodeButton.isVisible = false
        binding.enrolBody.isVisible = false

        val code = buildOwnEnrolment()
        binding.enrolWords.text = requestWords(code.enrolPubkeyHex).orEmpty()
        binding.enrolWords.isVisible = true
        binding.enrolWordsHint.isVisible = true

        val event = runCatching { UnlockNostr.inviteReplyEvent(invite, code.encode()) }.getOrElse {
            binding.enrolStatus.text = getString(R.string.enrol_send_failed)
            return
        }
        pendingInviteReply = event
        pendingInviteRelays = invite.relays
        binding.enrolStatus.text = getString(R.string.enrol_sending)

        val combined = (relays + invite.relays).distinct()
        // Not lifecycleScope: a start cancelled half way would leave a relay client nobody can
        // stop. The watch is recorded, or stopped at once if the screen closed meanwhile. The
        // publish itself waits for this same connect, so the reply is never lost to the race
        // between "connected" and "about to publish".
        CoroutineScope(Dispatchers.IO).launch {
            val started = RelayWatch.handOff(combined, code.rendezvous) { raw -> runOnUiThread { onHandOff(raw) } }
            // Views and `watch` belong to the UI thread, as in startHandOffWatch.
            runOnUiThread {
                if (destroyed) {
                    CoroutineScope(Dispatchers.IO).launch { started.stop() }
                } else {
                    watch = started
                    publishInviteReply()
                }
            }
        }
    }

    /** Publishes [pendingInviteReply] to [pendingInviteRelays]. Retry calls this again, unchanged. */
    private fun publishInviteReply() {
        val event = pendingInviteReply ?: return
        val current = watch ?: return
        binding.enrolRetryButton.isVisible = false
        binding.enrolStatus.text = getString(R.string.enrol_sending)
        CoroutineScope(Dispatchers.IO).launch {
            val ok = current.publish(event, pendingInviteRelays)
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (ok) {
                    binding.enrolStatus.text = getString(R.string.enrol_sent)
                } else {
                    binding.enrolStatus.text = getString(R.string.enrol_send_failed)
                    binding.enrolRetryButton.isVisible = true
                }
            }
        }
    }

    // -- Shared by both paths. --

    private fun buildOwnEnrolment(): EnrolmentCode {
        val (secretHex, pubkeyHex) = UnlockNostr.newEnrolmentKey()
        enrolSecretHex = secretHex
        val rendezvous = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        return EnrolmentCode(pubkeyHex, rendezvous, EnrolmentCode.fitLabel(Build.MODEL), relays)
    }

    private fun startHandOffWatch(onRelays: List<String>, rendezvous: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val started = RelayWatch.handOff(onRelays, rendezvous) { raw -> runOnUiThread { onHandOff(raw) } }
            runOnUiThread {
                if (destroyed) CoroutineScope(Dispatchers.IO).launch { started.stop() } else watch = started
            }
        }
    }

    private fun onHandOff(raw: RawAnnouncement) {
        if (conflicted || isFinishing) return
        val secret = enrolSecretHex ?: return
        val envelope = HandOffParser.envelope(raw.content) ?: return
        val decrypted = UnlockNostr.openHandOff(secret, envelope.ephemeralPubkeyHex, envelope.sealed) ?: return
        val handOff = HandOffParser.plain(decrypted, envelope) ?: return
        if (stored) {
            // Kept listening while the screen is open: a different answer after the fingerprint
            // means the one kept may be someone else's.
            if (handOff.id != storedId) {
                conflicted = true
                binding.enrolBatteryButton.isVisible = false
                binding.enrolStatus.text = getString(R.string.enrol_conflict_after, storedId, handOff.id)
                showCheckCode(false)
            }
            handOff.wipe()
            return
        }
        val first = pending
        if (first != null) {
            // The same answer again (a relay repeating it) is harmless; a different one is not.
            if (first.id != handOff.id || !first.slotSecret.contentEquals(handOff.slotSecret)) {
                conflicted = true
                first.wipe()
                pending = null
                enrolSecretHex = null
                binding.enrolConfirmButton.isVisible = false
                binding.enrolStatus.text = getString(R.string.enrol_conflict, first.id, handOff.id)
                showCheckCode(false)
            }
            handOff.wipe()
            return
        }
        if (UnlockStore(this).enrolment(handOff.id) != null) {
            handOff.wipe()
            binding.enrolStatus.text = getString(R.string.enrol_already_held, handOff.id)
            return
        }
        pending = handOff
        binding.enrolQr.isVisible = false
        binding.enrolCode.isVisible = false
        binding.enrolWords.isVisible = false
        binding.enrolWordsHint.isVisible = false
        binding.enrolCopyButton.isVisible = false
        binding.enrolRetryButton.isVisible = false
        binding.enrolStatus.text = getString(R.string.enrol_received)
        binding.enrolCheckCode.text = checkCode(envelope.ephemeralPubkeyHex).orEmpty()
        showCheckCode(true)
        binding.enrolConfirmButton.isVisible = true
        lifecycleScope.launch {
            delay(PENDING_TIMEOUT_MILLIS)
            val held = pending
            if (!stored && held === handOff) {
                held.wipe()
                pending = null
                enrolSecretHex = null
                binding.enrolConfirmButton.isVisible = false
                binding.enrolStatus.text = getString(R.string.enrol_timed_out, handOff.id)
                showCheckCode(false)
            }
        }
        promptSeal()
    }

    private fun promptSeal() {
        if (conflicted) return
        val handOff = pending ?: return
        val alias = keyAlias ?: SlotSecretVault.newAlias().also { keyAlias = it }
        val cipher: Cipher = runCatching { SlotSecretVault.encryptCipher(alias) }.getOrElse {
            binding.enrolStatus.text = getString(R.string.enrol_keystore_failed, it.message ?: it.javaClass.simpleName)
            return
        }
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.cipher ?: return
                    // A conflicting answer or the timeout may have landed while the prompt was up.
                    if (conflicted || pending !== handOff) return
                    finishEnrolment(handOff, alias, SlotSecretVault.seal(unlocked, handOff.slotSecret))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    binding.enrolStatus.text = getString(R.string.enrol_not_confirmed, handOff.id)
                }
            },
        ).authenticate(
            SlotSecretVault.promptInfo(
                getString(R.string.enrol_prompt_title),
                pairing.displayLabel(),
                getString(android.R.string.cancel),
            ),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    private fun finishEnrolment(handOff: HandOff, alias: String, sealedSecret: String) {
        val phoneKey = PhoneUnlock.phoneKey(handOff.slotSecret)
        UnlockStore(this).put(
            UnlockEnrolment(
                id = handOff.id,
                boardLabel = shortLabel(pairing),
                signerPubkeyHex = pairing.signerPubkeyHex,
                relays = (handOff.relays + pairing.relays).map { it.trimEnd('/') }.filter(::isRelayUrl).distinct(),
                phoneKeyHex = phoneKey.toHex(),
                keyAlias = alias,
                sealedSecretB64 = sealedSecret,
                enrolledAtMillis = System.currentTimeMillis(),
            ),
        )
        phoneKey.fill(0)
        handOff.wipe()
        pending = null
        stored = true
        storedId = handOff.id

        // A phone that can unlock is only useful if it is listening when the power comes back.
        PairingStore(this).setKeepAliveEnabled(true)
        HeartwoodKeepAliveService.start(this)

        // Left showing on Done: the owner may still be comparing it with Sapwood or the board's
        // own card at this point, and it stays harmless to see once the slot secret is sealed.
        binding.enrolConfirmButton.isVisible = false
        binding.enrolStatus.text = getString(R.string.enrol_done, handOff.id)
        binding.enrolBatteryButton.isVisible = !isIgnoringBatteryOptimisations(this)
        binding.enrolDoneButton.setText(R.string.enrol_finished)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        // Cambium's whole job here is to be reachable after a power cut at home while the owner
        // is away; Android's battery optimisation would stop the listener exactly then.
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        binding.enrolBatteryButton.isVisible = false
    }

    /** A pairing without a label would put a whole npub in the title; a short form reads better. */
    private fun shortLabel(pairing: Pairing): String =
        pairing.label?.takeIf { it.isNotBlank() } ?: pairing.displayLabel().let { if (it.length > 20) it.take(12) + "…" else it }

    /** The check code and its label/hint always show or hide together. */
    private fun showCheckCode(visible: Boolean) {
        binding.enrolCheckCodeLabel.isVisible = visible
        binding.enrolCheckCode.isVisible = visible
        binding.enrolCheckCodeHint.isVisible = visible
    }

    private fun showOnly(message: String) {
        binding.enrolBody.isVisible = false
        binding.enrolScanButton.isVisible = false
        binding.enrolShowCodeButton.isVisible = false
        binding.enrolShowCodeBody.isVisible = false
        binding.enrolQr.isVisible = false
        binding.enrolCode.isVisible = false
        binding.enrolWords.isVisible = false
        binding.enrolWordsHint.isVisible = false
        binding.enrolCopyButton.isVisible = false
        binding.enrolStatus.text = message
    }

    companion object {
        private const val EXTRA_SIGNER_PUBKEY = "signer_pubkey"
        /** How long an opened hand-off waits for the fingerprint before the secret is dropped. */
        private const val PENDING_TIMEOUT_MILLIS = 5 * 60 * 1000L

        fun intent(context: Context, signerPubkeyHex: String): Intent =
            Intent(context, UnlockEnrolActivity::class.java).putExtra(EXTRA_SIGNER_PUBKEY, signerPubkeyHex)

        fun isIgnoringBatteryOptimisations(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
}

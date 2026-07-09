package dev.forgesworn.cambium.nip55

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.databinding.ActivitySignerApprovalBinding
import dev.forgesworn.cambium.pairing.Pairing
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.signer.CacheableDecrypt
import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.HeartwoodSession
import kotlinx.coroutines.launch

private const val EXTRA_TYPE = "type"
private const val EXTRA_ID = "id"
private const val EXTRA_CURRENT_USER = "current_user"
private const val EXTRA_PUBKEY = "pubkey"
private const val EXTRA_PUBKEY_ALT = "pubKey" // some clients (and older Amber versions) send this casing
private const val EXTRA_RESULT = "result"
private const val EXTRA_EVENT = "event"
private const val EXTRA_SIGNATURE = "signature" // legacy Amber extra: raw hex sig, duplicating `result`/`event`
private const val EXTRA_PACKAGE = "package"
private const val EXTRA_REJECTED = "rejected"

/**
 * Handles `nostrsigner:` intents from Amber-compatible clients (Amethyst, Primal, Voyage, ...).
 * Every request that is not a locally-answerable `get_public_key` is forwarded to the paired
 * Heartwood over NIP-46; nothing here ever sees a private key.
 *
 * Continued daily use showed the visible progress overlay appearing for *every* request that fell
 * through to the intent path, even for callers already approved -- popups regularly, not just on
 * first login. This activity is now [silent] for an already-approved caller: no content view, no
 * dim, a transparent [R.style.Theme_Cambium_Invisible] theme swapped in before any window setup,
 * just the request running on [HeartwoodSession]'s worker with a `setResult`/`finish()` at the
 * end -- at most a sub-second flash. The visible Approve/Decline sheet and the "asking your
 * signer" progress overlay exist only for the *first* approval of a calling app (matches Amber's
 * remembered-choice UX). [silent] is decided once, in `onCreate`, and reused for the lifetime of
 * this activity instance -- it is not re-evaluated in [onNewIntent], since switching from a
 * visible theme to an invisible one after the window already exists is unreliable. Deliberately no
 * custom back-press handling for the silent case either: the request keeps running on the worker
 * regardless of what happens to this activity (see [HeartwoodSession]), so there is nothing to
 * intercept.
 *
 * `singleTop`: a client can fire a second request (e.g. `sign_event` right after
 * `get_public_key`) before the user has dismissed this activity, which arrives via
 * [onNewIntent] rather than a new instance. Each intent is handled sequentially in full; true
 * single-intent batch requests (NIP-55's `results` JSON-array response) are not implemented yet.
 */
class SignerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignerApprovalBinding
    private lateinit var pairingStore: PairingStore
    private var request: Nip55Request? = null
    private var silent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pairingStore = PairingStore(this)

        val callingPkg = callingPackage
        silent = callingPkg != null && pairingStore.isApproved(callingPkg)
        if (silent) {
            // Must happen before setContentView (and there is no content view to set here) for
            // the transparent/non-dimmed theme to actually take effect on the window.
            setTheme(R.style.Theme_Cambium_Invisible)
        }

        binding = ActivitySignerApprovalBinding.inflate(layoutInflater)
        if (!silent) {
            setContentView(binding.root)
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (!silent) {
            binding.decisionGroup.isVisible = false
            binding.progressGroup.isVisible = false
        }

        val raw = intent.toRawSignerIntent()
        val parsed = Nip55Request.from(raw)
        if (parsed == null) {
            rejectAndFinish(raw.id)
            return
        }
        request = parsed

        val pairing = pairingStore.current()
        if (pairing == null) {
            if (!silent) Toast.makeText(this, R.string.error_not_paired, Toast.LENGTH_LONG).show()
            rejectAndFinish(parsed.id)
            return
        }

        if (silent) {
            handle(pairing, parsed)
        } else {
            showApprovalSheet(pairing, parsed, callingPackage)
        }
    }

    private fun showApprovalSheet(pairing: Pairing, request: Nip55Request, callingPkg: String?) {
        binding.appValue.text = callingPkg?.let(::resolveCallerDisplayName) ?: "unknown caller"
        binding.methodValue.text = methodLabel(request)

        val kind = (request as? Nip55Request.SignEvent)?.let { extractEventKind(it.eventJson) }
        binding.kindRow.isVisible = kind != null
        if (kind != null) {
            binding.kindValue.text = kind.toString()
        }

        binding.decisionGroup.isVisible = true
        binding.progressGroup.isVisible = false

        binding.approveButton.setOnClickListener {
            callingPkg?.let { pairingStore.approve(it) }
            handle(pairing, request)
        }
        binding.declineButton.setOnClickListener {
            rejectAndFinish(request.id)
        }
    }

    /**
     * [request] is already approved for the calling app (silent, [silent] is true), or is being
     * approved right now (visible, the user just tapped Approve on the sheet).
     */
    private fun handle(pairing: Pairing, request: Nip55Request) {
        if (!silent) binding.decisionGroup.isVisible = false

        if (request is Nip55Request.GetPublicKey) {
            // Answered from the pairing record: no relay round trip needed once paired.
            respondSuccess(request, pairing.signerPubkeyHex, isPublicKeyRequest = true)
            return
        }

        if (!silent) binding.progressGroup.isVisible = true
        lifecycleScope.launch {
            val result = HeartwoodSession.withClient(pairing, cacheableFor(request)) { client ->
                when (request) {
                    is Nip55Request.SignEvent -> client.signEvent(request.eventJson)
                    is Nip55Request.Nip04Encrypt -> client.nip04Encrypt(request.pubkeyHex, request.plaintext)
                    is Nip55Request.Nip04Decrypt -> client.nip04Decrypt(request.pubkeyHex, request.ciphertext)
                    is Nip55Request.Nip44Encrypt -> client.nip44Encrypt(request.pubkeyHex, request.plaintext)
                    is Nip55Request.Nip44Decrypt -> client.nip44Decrypt(request.pubkeyHex, request.ciphertext)
                    is Nip55Request.GetPublicKey -> error("handled above without a relay round trip")
                }
            }

            when (result) {
                is HeartwoodResult.Success -> respondSuccess(
                    request,
                    result.value,
                    isPublicKeyRequest = false,
                )
                is HeartwoodResult.Failure -> showErrorAndReject(request.id, result.error)
            }
        }
    }

    private fun respondSuccess(request: Nip55Request, value: String, isPublicKeyRequest: Boolean) {
        val data = Intent().apply {
            putExtra(EXTRA_RESULT, value)
            putExtra(EXTRA_ID, request.id)
            if (request is Nip55Request.SignEvent) {
                putExtra(EXTRA_EVENT, value)
                // Legacy Amber compat: some clients still read the bare hex signature here
                // instead of pulling `sig` out of the event JSON in `result`/`event`.
                putExtra(EXTRA_SIGNATURE, extractEventSignatureHex(value) ?: value)
            }
            if (isPublicKeyRequest) {
                putExtra(EXTRA_PACKAGE, packageName)
            }
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun showErrorAndReject(id: String?, error: HeartwoodError) {
        // Silent means invisible end to end: an already-approved caller must never see a Toast
        // either, matching the "no UI at all" contract.
        if (!silent) {
            val message = when (error) {
                HeartwoodError.NotConnected -> getString(R.string.error_not_paired)
                HeartwoodError.Timeout -> getString(R.string.error_timeout)
                is HeartwoodError.InvalidInput -> error.message
                is HeartwoodError.Protocol -> error.message
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        rejectAndFinish(id)
    }

    private fun rejectAndFinish(id: String?) {
        val data = Intent().apply {
            putExtra(EXTRA_REJECTED, true)
            putExtra(EXTRA_ID, id)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun methodLabel(request: Nip55Request): String = getString(
        when (request) {
            is Nip55Request.GetPublicKey -> R.string.method_get_public_key
            is Nip55Request.SignEvent -> R.string.method_sign_event
            is Nip55Request.Nip04Encrypt -> R.string.method_nip04_encrypt
            is Nip55Request.Nip04Decrypt -> R.string.method_nip04_decrypt
            is Nip55Request.Nip44Encrypt -> R.string.method_nip44_encrypt
            is Nip55Request.Nip44Decrypt -> R.string.method_nip44_decrypt
        }
    )

    /** `null` for anything but the two decrypt methods -- see [CacheableDecrypt]. */
    private fun cacheableFor(request: Nip55Request): CacheableDecrypt? = when (request) {
        is Nip55Request.Nip04Decrypt -> CacheableDecrypt(CacheableDecrypt.Method.NIP04, request.pubkeyHex, request.ciphertext)
        is Nip55Request.Nip44Decrypt -> CacheableDecrypt(CacheableDecrypt.Method.NIP44, request.pubkeyHex, request.ciphertext)
        else -> null
    }

    /**
     * The calling package's human-readable label, for the approval sheet. Falls back to the raw
     * package string on any failure -- a live test against a real device showed this can throw
     * (package-visibility restrictions blocked the label lookup even though [getCallingPackage]
     * itself resolved fine), and the app line must never render blank.
     */
    private fun resolveCallerDisplayName(callingPkg: String): String = runCatching {
        val appInfo = packageManager.getApplicationInfo(callingPkg, 0)
        packageManager.getApplicationLabel(appInfo).toString()
    }.getOrDefault(callingPkg)
}

/** The only place an `android.content.Intent` is read; everything past this is plain Kotlin. */
private fun Intent.toRawSignerIntent(): RawSignerIntent = RawSignerIntent(
    payload = data?.schemeSpecificPart,
    type = getStringExtra(EXTRA_TYPE),
    id = getStringExtra(EXTRA_ID),
    currentUser = getStringExtra(EXTRA_CURRENT_USER),
    pubkey = getStringExtra(EXTRA_PUBKEY) ?: getStringExtra(EXTRA_PUBKEY_ALT),
)

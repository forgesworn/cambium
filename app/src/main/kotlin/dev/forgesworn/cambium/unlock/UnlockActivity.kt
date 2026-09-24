package dev.forgesworn.cambium.unlock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.databinding.ActivityUnlockBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The unlock prompt a notification opens: which board, why it restarted, on which network, the
 * standing warning, and one button that asks for a strong biometric. The biometric releases the
 * board's slot secret inside the Keystore ([SlotSecretVault]); its byte array is wiped once
 * [UnlockCoordinator.deliver] has published (see [PhoneUnlock] for the String copies it cannot wipe).
 * After the fingerprint it re-checks that the request is still the board's current one: a board
 * that restarted meanwhile has discarded the key the delivery would go to.
 *
 * It always acts on the board's *current* request ([UnlockCoordinator.currentRequest]), never on
 * what the notification said when it was posted: the board repeats its message every minute
 * while locked, so a current one is always at most a minute away, and an old notification can
 * never send a secret to a one-time key the board has since discarded.
 */
class UnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockBinding
    private lateinit var store: UnlockStore
    private var enrolmentId: Long = -1
    private var sending = false
    /** Once this screen has sent the unlock, it has done its job: no second send from it. */
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = UnlockStore(this)
        binding.unlockConfirmButton.setOnClickListener { onUnlockClicked() }
        binding.unlockCloseButton.setOnClickListener { finish() }
        if (!showBoard(intent)) return

        // Tapping a notification can start a fresh process: make sure the listener is up so the
        // board's next repeat reaches this screen.
        lifecycleScope.launch { UnlockCoordinator.sync(this@UnlockActivity) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { UnlockCoordinator.requests.collect { render() } }
                // Keeps "asked N s ago" and the freshness cut-off honest while the screen is open.
                while (true) {
                    delay(5_000)
                    render()
                }
            }
        }
    }

    /** A tap on another board's notification while this screen is open switches to that board. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (showBoard(intent)) render()
    }

    private fun showBoard(intent: Intent): Boolean {
        enrolmentId = intent.getLongExtra(EXTRA_ENROLMENT_ID, -1)
        sent = false
        sending = false
        binding.unlockStatus.isVisible = false
        binding.unlockConfirmButton.isVisible = true
        binding.unlockCloseButton.setText(R.string.unlock_not_now)
        val enrolment = store.enrolment(enrolmentId)
        if (enrolment == null) {
            Toast.makeText(this, R.string.unlock_board_forgotten, Toast.LENGTH_LONG).show()
            finish()
            return false
        }
        binding.unlockTitle.text = getString(R.string.unlock_title, enrolment.boardLabel)
        return true
    }

    private fun render() {
        val enrolment = store.enrolment(enrolmentId) ?: return finish()
        val request = UnlockCoordinator.currentRequest(enrolmentId)
        binding.unlockLastHeard.text = UnlockNotifications.lastHeard(this, enrolment).orEmpty()
        binding.unlockLastHeard.isVisible = binding.unlockLastHeard.text.isNotEmpty()
        if (sent) return showSent()
        if (request == null) {
            binding.unlockContext.text = getString(R.string.unlock_waiting)
            binding.unlockDetails.isVisible = false
            binding.unlockConfirmButton.isEnabled = false
            return
        }
        val lock = request.match.context
        binding.unlockContext.text = UnlockNotifications.describe(this, lock)
        val askedSecs = ((System.currentTimeMillis() - request.seenAtMillis) / 1000).coerceAtLeast(0)
        binding.unlockDetails.text = getString(
            R.string.unlock_details,
            lock.fw.ifBlank { "?" },
            lock.bssid.ifBlank { "?" },
            askedSecs,
        )
        binding.unlockDetails.isVisible = true
        binding.unlockConfirmButton.isEnabled = !sending
    }

    private fun showSent() {
        binding.unlockConfirmButton.isVisible = false
        binding.unlockCloseButton.setText(R.string.enrol_close)
    }

    private fun onUnlockClicked() {
        val enrolment = store.enrolment(enrolmentId) ?: return finish()
        val request = UnlockCoordinator.currentRequest(enrolmentId) ?: return render()
        when (val opening = SlotSecretVault.decryptCipher(enrolment.keyAlias, enrolment.sealedSecretB64)) {
            SlotSecretVault.Opening.KeyGone -> showStatus(getString(R.string.unlock_key_gone))
            is SlotSecretVault.Opening.Ready -> {
                val prompt = BiometricPrompt(
                    this,
                    ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val cipher = result.cryptoObject?.cipher ?: return
                            val secret = runCatching { cipher.doFinal(opening.ciphertext) }.getOrNull()
                            if (secret == null) {
                                showStatus(getString(R.string.unlock_key_gone))
                                return
                            }
                            send(request, secret)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                                showStatus(errString.toString())
                            }
                        }
                    },
                )
                prompt.authenticate(
                    SlotSecretVault.promptInfo(
                        getString(R.string.unlock_title, enrolment.boardLabel),
                        UnlockNotifications.describe(this, request.match.context),
                        getString(android.R.string.cancel),
                    ),
                    BiometricPrompt.CryptoObject(opening.cipher),
                )
            }
        }
    }

    private fun send(request: UnlockCoordinator.Request, secret: ByteArray) {
        // The board may have restarted while the prompt was open: its old one-time key is gone,
        // and the new restart's request must be read and approved on its own.
        val current = UnlockCoordinator.currentRequest(request.match.enrolment.id)
        if (current?.match?.announcement?.authorHex != request.match.announcement.authorHex) {
            secret.fill(0)
            showStatus(getString(R.string.unlock_request_changed))
            render()
            return
        }
        sending = true
        binding.unlockConfirmButton.isEnabled = false
        showStatus(getString(R.string.unlock_sending))
        lifecycleScope.launch {
            val sent = try {
                UnlockCoordinator.deliver(this@UnlockActivity, request, secret)
            } finally {
                secret.fill(0)
            }
            sending = false
            this@UnlockActivity.sent = sent
            showStatus(getString(if (sent) R.string.unlock_sent else R.string.unlock_send_failed))
            render()
        }
    }

    private fun showStatus(text: String) {
        binding.unlockStatus.text = text
        binding.unlockStatus.isVisible = true
    }

    companion object {
        private const val EXTRA_ENROLMENT_ID = "enrolment_id"

        fun intent(context: Context, enrolmentId: Long): Intent =
            Intent(context, UnlockActivity::class.java)
                // Distinct per board, so each board's notification keeps its own PendingIntent.
                .setData(Uri.parse("cambium-unlock://board/$enrolmentId"))
                .putExtra(EXTRA_ENROLMENT_ID, enrolmentId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

package dev.forgesworn.cambium.nip55

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.databinding.ActivitySignerApprovalBinding
import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.Pairing
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.signer.HeartwoodClient
import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.RustNostrHeartwoodClient
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val EXTRA_TYPE = "type"
private const val EXTRA_ID = "id"
private const val EXTRA_CURRENT_USER = "current_user"
private const val EXTRA_PUBKEY = "pubkey"
private const val EXTRA_RESULT = "result"
private const val EXTRA_EVENT = "event"
private const val EXTRA_PACKAGE = "package"
private const val EXTRA_REJECTED = "rejected"

/**
 * Handles `nostrsigner:` intents from Amber-compatible clients (Amethyst, Primal, Voyage, ...).
 * Every request that is not a locally-answerable `get_public_key` is forwarded to the paired
 * Heartwood over NIP-46; nothing here ever sees a private key.
 */
class SignerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignerApprovalBinding
    private lateinit var pairingStore: PairingStore
    private var request: Nip55Request? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignerApprovalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pairingStore = PairingStore(this)

        val raw = intent.toRawSignerIntent()
        val parsed = Nip55Request.from(raw)
        if (parsed == null) {
            rejectAndFinish(raw.id)
            return
        }
        request = parsed

        val pairing = pairingStore.current()
        if (pairing == null) {
            Toast.makeText(this, R.string.error_not_paired, Toast.LENGTH_LONG).show()
            rejectAndFinish(parsed.id)
            return
        }

        val callingPkg = callingPackage
        if (callingPkg != null && pairingStore.isApproved(callingPkg)) {
            handle(pairing, parsed)
        } else {
            showApprovalSheet(pairing, parsed, callingPkg)
        }
    }

    private fun showApprovalSheet(pairing: Pairing, request: Nip55Request, callingPkg: String?) {
        binding.appValue.text = callingPkg ?: "unknown caller"
        binding.methodValue.text = methodLabel(request)

        val kind = (request as? Nip55Request.SignEvent)?.let { extractKind(it.eventJson) }
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

    /** [request] is already approved for the calling app (or is being approved right now). */
    private fun handle(pairing: Pairing, request: Nip55Request) {
        binding.decisionGroup.isVisible = false

        if (request is Nip55Request.GetPublicKey) {
            // Answered from the pairing record: no relay round trip needed once paired.
            respondSuccess(request, pairing.signerPubkeyHex, isPublicKeyRequest = true)
            return
        }

        binding.progressGroup.isVisible = true
        lifecycleScope.launch {
            val client: HeartwoodClient = RustNostrHeartwoodClient()
            val bunkerUri = BunkerUri(pairing.signerPubkeyHex, pairing.relays, pairing.secret).toUriString()

            when (val connected = client.connect(bunkerUri, pairing.clientSecretKeyHex)) {
                is HeartwoodResult.Failure -> {
                    showErrorAndReject(request.id, connected.error)
                    client.disconnect()
                    return@launch
                }
                is HeartwoodResult.Success -> Unit
            }

            val result = when (request) {
                is Nip55Request.SignEvent -> client.signEvent(request.eventJson)
                is Nip55Request.Nip04Encrypt -> client.nip04Encrypt(request.pubkeyHex, request.plaintext)
                is Nip55Request.Nip04Decrypt -> client.nip04Decrypt(request.pubkeyHex, request.ciphertext)
                is Nip55Request.Nip44Encrypt -> client.nip44Encrypt(request.pubkeyHex, request.plaintext)
                is Nip55Request.Nip44Decrypt -> client.nip44Decrypt(request.pubkeyHex, request.ciphertext)
                is Nip55Request.GetPublicKey -> error("handled above without a relay round trip")
            }
            client.disconnect()

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
            }
            if (isPublicKeyRequest) {
                putExtra(EXTRA_PACKAGE, packageName)
            }
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun showErrorAndReject(id: String?, error: HeartwoodError) {
        val message = when (error) {
            HeartwoodError.NotConnected -> getString(R.string.error_not_paired)
            HeartwoodError.Timeout -> getString(R.string.error_timeout)
            is HeartwoodError.InvalidInput -> error.message
            is HeartwoodError.Protocol -> error.message
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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

    private fun extractKind(eventJson: String): Int? = runCatching {
        JSONObject(eventJson).getInt("kind")
    }.getOrNull()
}

/** The only place an `android.content.Intent` is read; everything past this is plain Kotlin. */
private fun Intent.toRawSignerIntent(): RawSignerIntent = RawSignerIntent(
    payload = data?.schemeSpecificPart,
    type = getStringExtra(EXTRA_TYPE),
    id = getStringExtra(EXTRA_ID),
    currentUser = getStringExtra(EXTRA_CURRENT_USER),
    pubkey = getStringExtra(EXTRA_PUBKEY),
)

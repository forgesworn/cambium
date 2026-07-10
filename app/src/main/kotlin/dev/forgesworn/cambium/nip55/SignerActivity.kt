package dev.forgesworn.cambium.nip55

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.databinding.ActivitySignerApprovalBinding
import dev.forgesworn.cambium.displayNameFor
import dev.forgesworn.cambium.nip57.PrivateZap
import dev.forgesworn.cambium.nip57.ZapDecodeResult
import dev.forgesworn.cambium.pairing.AppPermission
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.pairing.IdentityRouting
import dev.forgesworn.cambium.pairing.Pairing
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.signer.CacheableDecrypt
import dev.forgesworn.cambium.signer.HeartwoodClient
import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.HeartwoodSession
import dev.forgesworn.cambium.signer.displayLabel
import kotlinx.coroutines.launch

private const val EXTRA_TYPE = "type"
private const val EXTRA_ID = "id"
private const val EXTRA_CURRENT_USER = "current_user"
private const val EXTRA_PUBKEY = "pubkey"
private const val EXTRA_PUBKEY_ALT = "pubKey" // some clients (and older Amber versions) send this casing
private const val EXTRA_PERMISSIONS = "permissions"
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
 * first login. This activity is now [silent] for a caller with a remembered choice, approved *or*
 * denied: no content view, no dim, a transparent [R.style.Theme_Cambium_Invisible] theme swapped
 * in before any window setup. An approved caller's request runs on [HeartwoodSession]'s worker
 * with a `setResult`/`finish()` at the end; a denied caller is rejected immediately, with no
 * Heartwood call at all -- either way, at most a sub-second flash. The visible Approve/Decline
 * sheet and the "asking your signer" progress overlay exist only for a caller with no remembered
 * choice yet (matches Amber's remembered-choice UX), or for an approved caller whose identity
 * routing cannot be resolved silently -- see [decideSilent] and [IdentityRouting]. [silent] is
 * decided once, in `onCreate`, and reused for the lifetime of this activity instance -- not
 * re-evaluated in [onNewIntent], since switching from a visible theme to an invisible one after
 * the window already exists is unreliable (nor the reverse: an invisible window can't reliably
 * grow the decorations a visible sheet needs after the fact either).
 *
 * While a silent request is actually in flight against Heartwood, [silentBackPressBlock] makes
 * back-press a no-op: a stray back-press finishing this activity mid-request would still let the
 * underlying job complete on [HeartwoodSession]'s worker (nothing there depends on this activity
 * being alive), but the *result* would never make it back to the calling app, since `setResult`
 * only means anything if this activity is still around to call it. Enabled only for the two
 * forwarding windows (`handle`, `handleDecryptZapEvent`) and only when [silent]; the visible
 * Approve/Decline sheet's back-press behaviour (back = same as Decline, Android's default) is
 * unchanged.
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
    private var callerPermission: AppPermission? = null

    /**
     * Decided once in `onCreate`, before any window setup -- see the class doc for why it can't
     * be re-evaluated afterwards. No longer a plain function of [callerPermission] alone the way
     * it was pre-0.3.0: an approved caller is only silent if [decideSilent] finds that the first
     * intent's identity routing will actually resolve without asking. Set directly rather than
     * computed, since it depends on that one-time decision, not on a field that stays constant
     * for the rest of the instance's lifetime.
     */
    private var silent = false

    /** See the class doc comment. Disabled outside the two silent forwarding windows. */
    private val silentBackPressBlock = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, silentBackPressBlock)
        pairingStore = PairingStore(this)

        callerPermission = callingPackage?.let(pairingStore::permission)
        silent = decideSilent(intent)
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

    /**
     * A remembered DENIED caller is always silent: rejected outright, no Heartwood call, nothing
     * to show. A remembered APPROVED caller is silent only if [intent]'s identity routing (see
     * [IdentityRouting]) will actually resolve to a pairing without asking -- an approved caller
     * whose `current_user` cannot be resolved must fall back to the visible sheet rather than
     * guess an identity, which is only possible if the theme was chosen correctly here, before
     * `onCreate` finishes. An unparsable intent, or nothing paired at all, is also silent: both
     * end in an immediate reject with no UI needed either way. Everything else -- no remembered
     * choice yet, or an approved caller whose routing does not resolve -- needs the visible sheet.
     */
    private fun decideSilent(intent: Intent): Boolean {
        val permission = callerPermission ?: return false
        if (permission.state == AppPermissionState.DENIED) return true

        val pairings = pairingStore.pairings()
        if (pairings.isEmpty()) return true
        val parsed = Nip55Request.from(intent.toRawSignerIntent()) ?: return true
        val routed = IdentityRouting.resolve(parsed.currentUser, permission.boundIdentityPubkeyHex, pairings)
        return routed is IdentityRouting.Result.Resolved
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        // A stray back-press must only ever be blocked while a forwarding call is actually in
        // flight, never across a whole activity lifetime -- reset before working out what this
        // intent even is.
        silentBackPressBlock.isEnabled = false

        if (!silent) {
            binding.decisionGroup.isVisible = false
            binding.denyAlwaysLink.isVisible = false
            binding.progressGroup.isVisible = false
        }

        val raw = intent.toRawSignerIntent()
        val parsed = Nip55Request.from(raw)
        if (parsed == null) {
            rejectAndFinish(raw.id)
            return
        }
        request = parsed

        val pairings = pairingStore.pairings()
        if (pairings.isEmpty()) {
            if (!silent) Toast.makeText(this, R.string.error_not_paired, Toast.LENGTH_LONG).show()
            rejectAndFinish(parsed.id)
            return
        }

        val permission = callerPermission
        when (permission?.state) {
            AppPermissionState.DENIED -> rejectAndFinish(parsed.id)
            AppPermissionState.APPROVED -> {
                val routed = IdentityRouting.resolve(parsed.currentUser, permission.boundIdentityPubkeyHex, pairings)
                when {
                    routed is IdentityRouting.Result.Resolved -> handle(routed.pairing, parsed)
                    !silent -> showApprovalSheet(pairings, parsed, callingPackage)
                    else -> {
                        // A later onNewIntent-delivered request from an approved caller whose
                        // current_user cannot be resolved: the window is already invisible-themed
                        // from the first intent (see decideSilent), so a reliable visible sheet
                        // isn't possible here. Reject rather than silently guessing an identity.
                        rejectAndFinish(parsed.id)
                    }
                }
            }
            null -> showApprovalSheet(pairings, parsed, callingPackage)
        }
    }

    private fun showApprovalSheet(pairings: List<Pairing>, request: Nip55Request, callingPkg: String?) {
        binding.appValue.text = callingPkg?.let(packageManager::displayNameFor) ?: "unknown caller"
        binding.methodValue.text = methodLabel(request)

        val kind = (request as? Nip55Request.SignEvent)?.let { extractEventKind(it.eventJson) }
        binding.kindRow.isVisible = kind != null
        if (kind != null) {
            binding.kindValue.text = kind.toString()
        }

        // Only worth showing once there is more than one pairing to choose between; with exactly
        // one, Approve always means that one pairing, same as before Cambium supported more.
        // Default selection: the current_user match if the request named one we have, else first.
        binding.identityRow.isVisible = pairings.size > 1
        if (pairings.size > 1) {
            binding.identityPicker.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                pairings.map { it.displayLabel() },
            )
            val currentUserHex = IdentityRouting.normaliseCurrentUser(request.currentUser)
            val defaultIndex = pairings.indexOfFirst { it.signerPubkeyHex.equals(currentUserHex, ignoreCase = true) }
            binding.identityPicker.setSelection(defaultIndex.takeIf { it >= 0 } ?: 0)
        }

        // Display only, per NIP-55's optional get_public_key `permissions` extra: Heartwood's own
        // ClientPolicy is what actually gates every request, this is not pre-authorisation.
        val permissions = (request as? Nip55Request.GetPublicKey)?.permissions.orEmpty()
        binding.permissionsRow.isVisible = permissions.isNotEmpty()
        if (permissions.isNotEmpty()) {
            binding.permissionsValue.text = summariseRequestedPermissions(permissions)
        }

        binding.decisionGroup.isVisible = true
        binding.denyAlwaysLink.isVisible = true
        binding.progressGroup.isVisible = false

        binding.approveButton.setOnClickListener {
            val chosen = pairings.getOrNull(binding.identityPicker.selectedItemPosition) ?: pairings.first()
            callingPkg?.let { pairingStore.approve(it, chosen.signerPubkeyHex) }
            handle(chosen, request)
        }
        binding.declineButton.setOnClickListener {
            rejectAndFinish(request.id)
        }
        binding.denyAlwaysLink.setOnClickListener {
            callingPkg?.let { pairingStore.deny(it) }
            rejectAndFinish(request.id)
        }
    }

    /**
     * [request] is already approved for the calling app (silent, [silent] is true), or is being
     * approved right now (visible, the user just tapped Approve on the sheet).
     */
    private fun handle(pairing: Pairing, request: Nip55Request) {
        if (!silent) {
            binding.decisionGroup.isVisible = false
            binding.denyAlwaysLink.isVisible = false
        }

        if (request is Nip55Request.GetPublicKey) {
            // Answered from the pairing record: no relay round trip needed once paired.
            respondSuccess(request, pairing.signerPubkeyHex, isPublicKeyRequest = true)
            return
        }

        if (request is Nip55Request.DecryptZapEvent) {
            handleDecryptZapEvent(pairing, request)
            return
        }

        submitAndRespond(pairing, request, cacheableFor(request)) { client ->
            when (request) {
                is Nip55Request.SignEvent -> client.signEvent(request.eventJson)
                is Nip55Request.Nip04Encrypt -> client.nip04Encrypt(request.pubkeyHex, request.plaintext)
                is Nip55Request.Nip04Decrypt -> client.nip04Decrypt(request.pubkeyHex, request.ciphertext)
                is Nip55Request.Nip44Encrypt -> client.nip44Encrypt(request.pubkeyHex, request.plaintext)
                is Nip55Request.Nip44Decrypt -> client.nip44Decrypt(request.pubkeyHex, request.ciphertext)
                is Nip55Request.GetPublicKey -> error("handled above without a relay round trip")
                is Nip55Request.DecryptZapEvent -> error("handled separately, see handleDecryptZapEvent")
            }
        }
    }

    /**
     * `decrypt_zap_event`: [PrivateZap.decodeAnonTag] runs locally first (no relay call) to turn
     * the zap request's `anon` tag into an ordinary nip04_decrypt call. Anything that isn't a
     * decryptable private zap -- wrong kind, no `anon` tag (an ordinary public zap), a malformed
     * `anon` tag, or a structurally broken event -- is rejected immediately; there is nothing
     * Heartwood could do with any of them. On a `Forward`, [PrivateZap.decryptAndValidate] and
     * [PrivateZap.cacheableFor] are shared with `SignerProvider.queryDecryptZapEvent`, so the
     * decrypt-then-check-kind-9733 call and its cache key exist exactly once.
     */
    private fun handleDecryptZapEvent(pairing: Pairing, request: Nip55Request.DecryptZapEvent) {
        val decoded = PrivateZap.decodeAnonTag(request.eventJson)
        val forward = when (decoded) {
            is ZapDecodeResult.Malformed,
            is ZapDecodeResult.NotAZapRequest,
            is ZapDecodeResult.NoAnonTag,
            is ZapDecodeResult.MalformedAnon,
            -> {
                rejectAndFinish(request.id)
                return
            }
            is ZapDecodeResult.Forward -> decoded
        }

        submitAndRespond(pairing, request, PrivateZap.cacheableFor(forward)) { client ->
            PrivateZap.decryptAndValidate(client, forward)
        }
    }

    /**
     * Shared tail for every forwarded request: shows the progress overlay (visible path) or arms
     * [silentBackPressBlock] (silent path), submits [operation] to [HeartwoodSession]'s worker,
     * and dispatches the outcome to [respondSuccess]/[showErrorAndReject]. [handle] and
     * [handleDecryptZapEvent] both funnel into this so the submit-and-respond pipeline exists once.
     */
    private fun submitAndRespond(
        pairing: Pairing,
        request: Nip55Request,
        cacheable: CacheableDecrypt?,
        operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
    ) {
        if (!silent) {
            binding.progressGroup.isVisible = true
        } else {
            silentBackPressBlock.isEnabled = true
        }
        lifecycleScope.launch {
            when (val result = HeartwoodSession.withClient(pairing, cacheable, operation)) {
                is HeartwoodResult.Success -> respondSuccess(request, result.value, isPublicKeyRequest = false)
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
            is Nip55Request.DecryptZapEvent -> R.string.method_decrypt_zap_event
        }
    )

    /** `null` for anything but the two plain decrypt methods -- see [CacheableDecrypt].
     * `DecryptZapEvent` builds its own [CacheableDecrypt] in [handleDecryptZapEvent] instead,
     * since its key needs the pubkey [PrivateZap] derives from the event, not one from the
     * request itself. */
    private fun cacheableFor(request: Nip55Request): CacheableDecrypt? = when (request) {
        is Nip55Request.Nip04Decrypt -> CacheableDecrypt(CacheableDecrypt.Method.NIP04, request.pubkeyHex, request.ciphertext)
        is Nip55Request.Nip44Decrypt -> CacheableDecrypt(CacheableDecrypt.Method.NIP44, request.pubkeyHex, request.ciphertext)
        else -> null
    }

}

/** The only place an `android.content.Intent` is read; everything past this is plain Kotlin. */
private fun Intent.toRawSignerIntent(): RawSignerIntent = RawSignerIntent(
    payload = data?.schemeSpecificPart,
    type = getStringExtra(EXTRA_TYPE),
    id = getStringExtra(EXTRA_ID),
    currentUser = getStringExtra(EXTRA_CURRENT_USER),
    pubkey = getStringExtra(EXTRA_PUBKEY) ?: getStringExtra(EXTRA_PUBKEY_ALT),
    permissions = getStringExtra(EXTRA_PERMISSIONS),
)

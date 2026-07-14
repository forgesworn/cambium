package dev.forgesworn.cambium.nip55

import android.content.Intent
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.applock.AppLockPrompt
import dev.forgesworn.cambium.applock.AppLockStore
import dev.forgesworn.cambium.databinding.ActivitySignerApprovalBinding
import dev.forgesworn.cambium.displayNameFor
import dev.forgesworn.cambium.log.ActivityLog
import dev.forgesworn.cambium.log.ActivityLogEntry
import dev.forgesworn.cambium.log.ActivityLogStore
import dev.forgesworn.cambium.nip57.PrivateZap
import dev.forgesworn.cambium.nip57.ZapDecodeResult
import dev.forgesworn.cambium.pairing.AppPermission
import dev.forgesworn.cambium.pairing.IdentityRouting
import dev.forgesworn.cambium.pairing.Pairing
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.signer.CacheableDecrypt
import dev.forgesworn.cambium.signer.HeartwoodClient
import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.HeartwoodSession
import dev.forgesworn.cambium.signer.displayLabel
import dev.forgesworn.cambium.signer.npubWire
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
 * routing cannot be resolved silently -- see [IntentGate] and [IdentityRouting]. [silent] is
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
 *
 * App lock (see `applock/AppLockPrompt.kt`) gates exactly two actions here -- Approve and "always
 * deny" on the visible sheet, via [requireUnlockedThen] -- never the silent forwarding path in
 * [handle]/[submitAndRespond], which must keep answering an already-approved caller with the
 * phone locked. Decline is not gated either: refusing needs no proof of presence.
 */
class SignerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignerApprovalBinding
    private lateinit var pairingStore: PairingStore
    private lateinit var activityLogStore: ActivityLogStore
    private lateinit var appLockStore: AppLockStore
    private var callerPermission: AppPermission? = null

    /**
     * Decided once in `onCreate`, before any window setup -- see the class doc for why it can't
     * be re-evaluated afterwards. No longer a plain function of [callerPermission] alone the way
     * it was pre-0.3.0: an approved caller is only silent if the first intent's [IntentGate.plan]
     * finds that its identity routing will actually resolve without asking -- see
     * [IntentGate.silentFor]. Set directly rather than computed, since it depends on that
     * one-time decision, not on a field that stays constant for the rest of the instance's
     * lifetime.
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
        activityLogStore = ActivityLogStore.getInstance(this)
        appLockStore = AppLockStore(this)

        callerPermission = callingPackage?.let(pairingStore::permission)
        val parsed = Nip55Request.from(intent.toRawSignerIntent())
        val plan = IntentGate.plan(callerPermission, pairingStore.pairings(), parsed, canAsk = true)
        silent = IntentGate.silentFor(callerPermission, plan)
        if (silent) {
            // Must happen before setContentView (and there is no content view to set here) for
            // the transparent/non-dimmed theme to actually take effect on the window.
            setTheme(R.style.Theme_Cambium_Invisible)
        }

        binding = ActivitySignerApprovalBinding.inflate(layoutInflater)
        if (!silent) {
            setContentView(binding.root)
        }

        handleIncomingIntent(intent, FirstIntent(parsed, plan))
    }

    /** The first intent's parse and [IntentGate.plan], computed in `onCreate` (where the plan
     * also picked the window theme, via [IntentGate.silentFor]) and reused by the very first
     * [handleIncomingIntent] call, so it does not re-parse the same intent and re-run identity
     * routing against it immediately afterwards. `onNewIntent`-delivered requests compute both
     * fresh, with `canAsk = !silent` -- see [IntentGate.plan]. */
    private data class FirstIntent(
        val parsed: Nip55Request?,
        val plan: IntentGate.Plan,
    )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent, precomputed = null)
    }

    private fun handleIncomingIntent(intent: Intent, precomputed: FirstIntent?) {
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
        // Not an elvis on precomputed.parsed: a precomputed null parse means the first intent was
        // unparsable, not that it needs parsing again.
        val parsed = if (precomputed != null) precomputed.parsed else Nip55Request.from(raw)
        if (parsed == null) {
            rejectAndFinish(raw.id)
            return
        }

        val pairings = pairingStore.pairings()
        val plan = precomputed?.plan
            ?: IntentGate.plan(callerPermission, pairings, parsed, canAsk = !silent)
        when (plan) {
            is IntentGate.Plan.Forward -> handle(plan.pairing, parsed)
            IntentGate.Plan.AskUser -> showApprovalSheet(pairings, parsed, callingPackage)
            is IntentGate.Plan.Reject -> when (plan.reason) {
                // Unreachable once parsed is known non-null (above); listed so the dispatch stays
                // exhaustive over every reason.
                IntentGate.RejectReason.MALFORMED_REQUEST -> rejectAndFinish(parsed.id)
                IntentGate.RejectReason.NOTHING_PAIRED -> {
                    if (!silent) Toast.makeText(this, R.string.error_not_paired, Toast.LENGTH_LONG).show()
                    rejectAndFinish(parsed.id)
                }
                IntentGate.RejectReason.DENIED_CALLER -> {
                    logActivity(parsed, identityLabel = null, outcome = ActivityLogEntry.Outcome.REJECTED_USER)
                    rejectAndFinish(parsed.id)
                }
                IntentGate.RejectReason.UNRESOLVED_IDENTITY -> {
                    // A later onNewIntent-delivered request from an approved caller whose
                    // current_user cannot be resolved: the window is already invisible-themed
                    // from the first intent (see IntentGate.plan's canAsk), so a reliable visible
                    // sheet isn't possible here. Reject rather than silently guessing an identity.
                    logActivity(parsed, identityLabel = null, outcome = ActivityLogEntry.Outcome.FAILED)
                    rejectAndFinish(parsed.id)
                }
            }
        }
    }

    /** Metadata only -- see [ActivityLogEntry]'s doc: never a payload, plaintext, ciphertext or
     * event content, just what was asked, by whom, answered how, and by which identity. Only
     * called for requests that reached an actual decision about the paired identity -- a
     * malformed/unparsable intent, "nothing paired at all", and `decrypt_zap_event`'s local
     * "not a decryptable private zap" outcomes (an ordinary public zap tag being the routine
     * case, not an error) are deliberately not logged, since they would mostly just be noise
     * rather than signal about what Cambium actually did on the user's behalf. */
    private fun logActivity(request: Nip55Request, identityLabel: String?, outcome: ActivityLogEntry.Outcome) {
        activityLogStore.append(
            ActivityLogEntry(
                timestampMillis = System.currentTimeMillis(),
                callingPackage = callingPackage ?: "unknown",
                method = methodLabel(request),
                eventKind = (request as? Nip55Request.SignEvent)?.let { extractEventKind(it.eventJson) },
                identityLabel = identityLabel,
                outcome = outcome,
            )
        )
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
        // Default selection precedence: the request's current_user match, else the caller's
        // existing bound identity, else the first pairing. In practice a current_user match can
        // never coexist with a *different* existing binding here -- this sheet only shows for an
        // already-approved caller when their current_user named an identity we don't have (see
        // IntentGate.plan/handleIncomingIntent) -- but identityRebindHint below still compares
        // whatever ends up selected against the binding directly, so a user who manually moves
        // the picker away from it sees a clear warning before Approve would silently rebind the
        // app, rather than relying on the precedence alone to prevent that.
        val boundPubkeyHex = callerPermission?.boundIdentityPubkeyHex
        binding.identityRow.isVisible = pairings.size > 1
        binding.identityRebindHint.isVisible = false
        if (pairings.size > 1) {
            binding.identityPicker.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                pairings.map { it.displayLabel() },
            )
            val preferredPubkeyHex = IdentityRouting.normaliseCurrentUser(request.currentUser) ?: boundPubkeyHex
            val defaultIndex = pairings.indexOfFirst { it.signerPubkeyHex.equals(preferredPubkeyHex, ignoreCase = true) }
                .takeIf { it >= 0 } ?: 0
            binding.identityPicker.setSelection(defaultIndex)

            val boundPairing = boundPubkeyHex?.let { hex -> pairings.firstOrNull { it.signerPubkeyHex.equals(hex, ignoreCase = true) } }
            fun updateRebindHint(position: Int) {
                val differs = boundPairing != null && pairings.getOrNull(position)?.signerPubkeyHex != boundPairing.signerPubkeyHex
                binding.identityRebindHint.isVisible = differs
                if (differs) {
                    binding.identityRebindHint.text = getString(R.string.approval_identity_rebind_hint, boundPairing!!.displayLabel())
                }
            }
            updateRebindHint(defaultIndex)
            binding.identityPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) =
                    updateRebindHint(position)
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
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
            requireUnlockedThen {
                val chosen = pairings.getOrNull(binding.identityPicker.selectedItemPosition) ?: pairings.first()
                callingPkg?.let { pairingStore.approve(it, chosen.signerPubkeyHex) }
                handle(chosen, request)
            }
        }
        binding.declineButton.setOnClickListener {
            logActivity(request, identityLabel = null, outcome = ActivityLogEntry.Outcome.REJECTED_USER)
            rejectAndFinish(request.id)
        }
        binding.denyAlwaysLink.setOnClickListener {
            requireUnlockedThen {
                callingPkg?.let { pairingStore.deny(it) }
                logActivity(request, identityLabel = null, outcome = ActivityLogEntry.Outcome.REJECTED_USER)
                rejectAndFinish(request.id)
            }
        }
    }

    /** Gates [action] behind app lock -- see the class doc for exactly which two actions call
     * this. A no-op passthrough when app lock is off, unavailable (fail-open -- see
     * [AppLockPrompt.requiresAuthenticationNow]), or still within the grace window. */
    private fun requireUnlockedThen(action: () -> Unit) {
        if (!AppLockPrompt.requiresAuthenticationNow(this, appLockStore)) {
            action()
            return
        }
        AppLockPrompt.authenticate(
            activity = this,
            title = getString(R.string.app_lock_prompt_title),
            onSuccess = {
                appLockStore.recordAuthenticated()
                action()
            },
            onFailure = {
                // Stay on the sheet; the user can tap Approve/"always deny" again to retry.
            },
        )
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
            // npub, not hex: Amber answers get_public_key with an npub and clients rely on
            // that shape (see npubWire's doc for the Primal failure a hex answer causes).
            logActivity(request, pairing.displayLabel(), ActivityLogEntry.Outcome.SIGNED)
            respondSuccess(request, npubWire(pairing.signerPubkeyHex), isPublicKeyRequest = true)
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
            val outcome = HeartwoodSession.withClient(pairing, cacheable, operation)
            logActivity(request, pairing.displayLabel(), ActivityLog.outcomeFor(outcome))
            when (val result = outcome.result) {
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

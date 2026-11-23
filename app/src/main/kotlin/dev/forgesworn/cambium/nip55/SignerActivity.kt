package dev.forgesworn.cambium.nip55

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
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
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.pairing.ApprovalLifetime
import dev.forgesworn.cambium.pairing.PermissionScope
import dev.forgesworn.cambium.pairing.PermissionScopeEditor
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import dev.forgesworn.cambium.signer.HeartwoodRequestPriority
import kotlinx.coroutines.delay
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

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
 * [onNewIntent] rather than a new instance. Explicit SINGLE_TOP + CLEAR_TOP batches queue
 * requests and return a bounded `results` array. Reused intents need authenticated caller
 * attribution or visible one-shot consent; they never borrow the first request's permission.
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

    /**
     * Decided once in `onCreate`, before any window setup -- see the class doc for why it can't
     * be re-evaluated afterwards. No longer a plain function of the caller permission alone the way
     * it was pre-0.3.0: an approved caller is only silent if the first intent's [IntentGate.plan]
     * finds that its identity routing will actually resolve without asking -- see
     * [IntentGate.silentFor]. Set directly rather than computed, since it depends on that
     * one-time decision, not on a field that stays constant for the rest of the instance's
     * lifetime.
     */
    @Volatile private var provisionSequence = 0L
    private var silent = false
    private var batchEnabled = false
    private var resultRecipient: String? = null
    private var requestInProgress = false
    private var receivedRequests = 0
    private var inputBytes = 0
    private val pendingNativeIntents = ArrayDeque<Pair<Intent, String?>>()
    private val batchResults = NativeBatchResults()
    private var finishBatchJob: Job? = null
    private var activeBatchRequest: Pair<Nip55Request, RequestContext>? = null
    private var waitingForApproval = false
    private var requestSequence = 0L

    private val batchBackPress = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // Once native work is handed off, retain this activity until its result arrives.
            if (!waitingForApproval) return
            val (request, context) = activeBatchRequest ?: return
            logActivity(request, context, null, ActivityLogEntry.Outcome.REJECTED_USER)
            rejectAndFinish(context)
        }
    }

    /** See the class doc comment. Disabled outside the two silent forwarding windows. */
    private val silentBackPressBlock = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, silentBackPressBlock)
        onBackPressedDispatcher.addCallback(this, batchBackPress)
        pairingStore = PairingStore(this)
        activityLogStore = ActivityLogStore.getInstance(this)
        appLockStore = AppLockStore(this)

        val requestContext = requestContextFor(intent)
        resultRecipient = requestContext.nativeCallingPackage
        batchEnabled = resultRecipient != null && hasBatchFlags(intent)
        batchBackPress.isEnabled = batchEnabled
        receivedRequests = 1
        inputBytes = nativeInputBytes(intent)
        val plan = IntentGate.plan(
            requestContext.permission,
            pairingStore.pairings(),
            requestContext.incoming.parsed,
            canAsk = true,
        )
        // A batch must be able to ask for an unattributable later intent on older Android.
        silent = requestContext.permission?.state == AppPermissionState.DENIED ||
            (!batchEnabled && IntentGate.silentFor(requestContext.permission, plan))
        if (silent) {
            // Must happen before setContentView (and there is no content view to set here) for
            // the transparent/non-dimmed theme to actually take effect on the window.
            setTheme(R.style.Theme_Cambium_Invisible)
        }

        binding = ActivitySignerApprovalBinding.inflate(layoutInflater)
        if (!silent) {
            setContentView(binding.root)
        }

        if (batchEnabled && (inputBytes > NativeBatchResults.MAX_INPUT_BYTES ||
                requestContext.incoming.raw.id.orEmpty().length > NativeBatchResults.MAX_ID_CHARS)) {
            Toast.makeText(this, R.string.batch_overflow, Toast.LENGTH_LONG).show()
            rejectAndFinish(requestContext)
        } else handleIncomingIntent(intent, FirstIntent(requestContext, plan))
    }

    /** The first intent's parse and [IntentGate.plan], computed in `onCreate` (where the plan
     * also picked the window theme, via [IntentGate.silentFor]) and reused by the very first
     * [handleIncomingIntent] call, so it does not re-parse the same intent and re-run identity
     * routing against it immediately afterwards. `onNewIntent`-delivered requests compute both
     * fresh, with `canAsk = !silent` -- see [IntentGate.plan]. */
    private data class FirstIntent(
        val context: RequestContext,
        val plan: IntentGate.Plan,
    )

    private data class RequestContext(
        val incoming: IncomingNip55Request,
        val permission: AppPermission?,
        val nativeCallingPackage: String?,
        val userApproved: Boolean = false,
    ) {
        val web: Nip55Transport.Web? get() = incoming.transport as? Nip55Transport.Web
        val logCaller: String
            get() {
                val webTransport = web
                return when {
                    webTransport?.callbackOrigin != null -> "web:${webTransport.callbackOrigin}"
                    webTransport != null -> "web:clipboard"
                    else -> nativeCallingPackage ?: "unknown"
                }
            }
    }

    private fun requestContextFor(intent: Intent, caller: String? = callingPackage): RequestContext {
        val incoming = IncomingNip55RequestParser.parse(intent.dataString, intent.toRawSignerIntent())
        val nativeCallingPackage = caller.takeIf { incoming.transport.canUsePackagePermission }
        return RequestContext(
            incoming = incoming,
            permission = nativeCallingPackage?.let(pairingStore::permission),
            nativeCallingPackage = nativeCallingPackage,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // callingPackage identifies the original result recipient, not a reused intent's sender.
        val sender = if (Build.VERSION.SDK_INT >= 35) {
            runCatching { currentCaller.getPackage() }.getOrNull()
        } else null
        if (!batchEnabled || !hasBatchFlags(intent)) return
        val incoming = IncomingNip55RequestParser.parse(intent.dataString, intent.toRawSignerIntent())
        if (!incoming.transport.canUsePackagePermission) return
        if (BatchCallerGate.decide(resultRecipient, sender) == BatchCallerGate.Decision.REJECT) return
        val bytes = nativeInputBytes(intent)
        if (receivedRequests >= NativeBatchResults.MAX_REQUESTS || inputBytes + bytes > NativeBatchResults.MAX_INPUT_BYTES) {
            Toast.makeText(this, R.string.batch_overflow, Toast.LENGTH_LONG).show()
            return
        }
        if (incoming.raw.id.orEmpty().length > NativeBatchResults.MAX_ID_CHARS) return
        receivedRequests++
        inputBytes += bytes
        if (!silent) binding.batchPosition.text = getString(R.string.batch_request_position,
            batchResults.size + 1, receivedRequests)
        finishBatchJob?.cancel()
        pendingNativeIntents.addLast(intent to sender)
        if (!requestInProgress) processNextNativeIntent()
    }

    private fun hasBatchFlags(intent: Intent): Boolean =
        intent.flags and (Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP) ==
            (Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun batchRecipientIsDenied(): Boolean = batchEnabled &&
        resultRecipient?.let(pairingStore::permission)?.state == AppPermissionState.DENIED

    private fun nativeInputBytes(intent: Intent): Int = listOfNotNull(intent.dataString,
        intent.getStringExtra(EXTRA_ID), intent.getStringExtra(EXTRA_TYPE), intent.getStringExtra(EXTRA_CURRENT_USER),
        intent.getStringExtra(EXTRA_PUBKEY), intent.getStringExtra(EXTRA_PUBKEY_ALT), intent.getStringExtra(EXTRA_PERMISSIONS))
        .sumOf { it.toByteArray(Charsets.UTF_8).size }

    private fun processNextNativeIntent() {
        val (next, sender) = pendingNativeIntents.removeFirstOrNull() ?: return
        val context = requestContextFor(next, sender)
        handleIncomingIntent(next, FirstIntent(context, IntentGate.plan(
            context.permission, pairingStore.pairings(), context.incoming.parsed, canAsk = !silent,
        )))
    }

    private fun handleIncomingIntent(intent: Intent, precomputed: FirstIntent?) {
        requestSequence++
        provisionSequence = requestSequence.toLong()
        waitingForApproval = false
        requestInProgress = true
        // A stray back-press must only ever be blocked while a forwarding call is actually in
        // flight, never across a whole activity lifetime -- reset before working out what this
        // intent even is.
        silentBackPressBlock.isEnabled = false

        if (!silent) {
            binding.decisionGroup.isVisible = false
            binding.denyAlwaysLink.isVisible = false
            binding.progressGroup.isVisible = false
        }

        val initialContext = precomputed?.context ?: requestContextFor(intent)
        val context = initialContext.copy(permission = initialContext.nativeCallingPackage?.let(pairingStore::permission))
        // Not an elvis on precomputed parsed data: a precomputed null parse means the first intent was
        // unparsable, not that it needs parsing again.
        val parsed = context.incoming.parsed
        if (parsed == null) {
            if (context.web != null && !silent) {
                Toast.makeText(this, R.string.error_invalid_web_request, Toast.LENGTH_LONG).show()
            }
            rejectAndFinish(context)
            return
        }
        if (parsed is Nip55Request.ProvisionRendezvous &&
            (context.web != null || context.nativeCallingPackage == null || batchEnabled)) {
            rejectAndFinish(context)
            return
        }
        if (batchEnabled) activeBatchRequest = parsed to context
        // Attribution can be absent for a reused intent; its result recipient is still known.
        // A blocked recipient cannot obtain signing or identity data through that one-shot path.
        if (batchRecipientIsDenied()) {
            logActivity(parsed, context, null, ActivityLogEntry.Outcome.REJECTED_USER)
            rejectAndFinish(context)
            return
        }

        // A web request must always have a visible, one-shot approval. If it races into an
        // already-invisible native activity via singleTop, fail closed instead of signing under
        // the browser package's remembered native permission or trying to mutate the window.
        if (context.web != null && silent && precomputed == null) {
            rejectAndFinish(context)
            return
        }

        val pairings = pairingStore.pairings()
        val plan = IntentGate.plan(context.permission, pairings, parsed, canAsk = !silent)
        when (plan) {
            is IntentGate.Plan.Forward -> handle(plan.pairing, parsed, context)
            IntentGate.Plan.AskUser -> showApprovalSheet(pairings, parsed, context)
            is IntentGate.Plan.Reject -> when (plan.reason) {
                // Unreachable once parsed is known non-null (above); listed so the dispatch stays
                // exhaustive over every reason.
                IntentGate.RejectReason.MALFORMED_REQUEST -> rejectAndFinish(context)
                IntentGate.RejectReason.NOTHING_PAIRED -> {
                    if (!silent) Toast.makeText(this, R.string.error_not_paired, Toast.LENGTH_LONG).show()
                    rejectAndFinish(context)
                }
                IntentGate.RejectReason.DENIED_CALLER -> {
                    logActivity(parsed, context, identityLabel = null, outcome = ActivityLogEntry.Outcome.REJECTED_USER)
                    rejectAndFinish(context)
                }
                IntentGate.RejectReason.UNRESOLVED_IDENTITY,
                IntentGate.RejectReason.PERMISSION_REQUIRES_CONSENT -> {
                    // A later onNewIntent-delivered request from an approved caller whose
                    // current_user cannot be resolved -- or resolved to an identity other than
                    // its bound one (see IdentityRouting.isBoundIdentity): the window is already
                    // invisible-themed from the first intent (see IntentGate.plan's canAsk), so a
                    // reliable visible sheet isn't possible here. Reject rather than silently
                    // guessing an identity, or signing as one the approval never covered.
                    logActivity(parsed, context, identityLabel = null, outcome = ActivityLogEntry.Outcome.FAILED)
                    rejectAndFinish(context)
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
    private fun logActivity(
        request: Nip55Request,
        context: RequestContext,
        identityLabel: String?,
        outcome: ActivityLogEntry.Outcome,
        failureCategory: ActivityLogEntry.FailureCategory? = null,
        durationMillis: Long? = null,
        identityReference: String? = null,
    ) {
        activityLogStore.append(
            ActivityLogEntry(
                timestampMillis = System.currentTimeMillis(),
                callingPackage = context.logCaller,
                method = methodLabel(request),
                eventKind = (request as? Nip55Request.SignEvent)?.let { extractEventKind(it.eventJson) },
                identityLabel = identityLabel,
                outcome = outcome,
                failureCategory = failureCategory,
                durationMillis = durationMillis,
                identityReference = identityReference,
            )
        )
    }

    private fun showRequestInformation(request: Nip55Request, context: RequestContext) {
        binding.callerLabel.setText(
            if (context.web != null) R.string.approval_web_return_label else R.string.approval_app_label
        )
        binding.appValue.text = context.web?.displayHost
            ?: context.nativeCallingPackage?.let(packageManager::displayNameFor)
            ?: if (context.web != null) getString(R.string.approval_web_clipboard_caller)
            else getString(R.string.approval_unknown_caller)
        binding.methodValue.text = methodLabel(request)
        val presentation = RequestPresenter.describe(request)
        binding.requestSummary.text = presentation.summary
        binding.requestDetailsButton.isVisible = presentation.details != null
        binding.requestDetailsButton.setOnClickListener {
            val details = TextView(this).apply {
                text = getString(R.string.request_details_note) + "\n\n" + presentation.details.orEmpty()
                setTextIsSelectable(true)
                setPadding(24, 16, 24, 16)
            }
            MaterialAlertDialogBuilder(this).setTitle(R.string.request_details_title)
                .setView(ScrollView(this).apply { addView(details) })
                .setPositiveButton(android.R.string.ok, null).show()
        }
        binding.batchPosition.isVisible = batchEnabled
        binding.batchPosition.text = getString(R.string.batch_request_position, batchResults.size + 1, receivedRequests)
        binding.batchCallerNote.isVisible = batchEnabled && context.nativeCallingPackage == null && context.web == null
        binding.batchCallerNote.text = getString(R.string.batch_unattributed_note,
            resultRecipient?.let(packageManager::displayNameFor) ?: getString(R.string.approval_unknown_caller))
        val kind = (request as? Nip55Request.SignEvent)?.let { extractEventKind(it.eventJson) }
        binding.kindRow.isVisible = kind != null
        if (kind != null) binding.kindValue.text = kind.toString()
    }

    private fun showApprovalSheet(availablePairings: List<Pairing>, request: Nip55Request, context: RequestContext) {
        val pairings = if (request is Nip55Request.ProvisionRendezvous) availablePairings.filter { it.signerPubkeyHex == request.provision.expectedPersona } else availablePairings
        if (batchRecipientIsDenied()) {
            rejectAndFinish(context)
            return
        }
        if (pairings.isEmpty()) {
            Toast.makeText(this, R.string.error_not_paired, Toast.LENGTH_LONG).show()
            rejectAndFinish(context)
            return
        }
        waitingForApproval = true
        val sheetSequence = requestSequence
        showRequestInformation(request, context)
        binding.webOneShotNote.isVisible = context.web != null
        binding.nativeRememberNote.isVisible = context.nativeCallingPackage != null && request !is Nip55Request.ProvisionRendezvous
        binding.nativePermissionControls.isVisible = context.nativeCallingPackage != null && request !is Nip55Request.ProvisionRendezvous
        val lifetimes = listOf(ApprovalLifetime.ONCE, ApprovalLifetime.HOUR, ApprovalLifetime.DAY, ApprovalLifetime.ALWAYS)
        binding.approvalLifetimePicker.adapter = ArrayAdapter(this, R.layout.item_identity_choice,
            listOf(R.string.approval_once, R.string.approval_hour, R.string.approval_day, R.string.approval_always).map(::getString))
        // Asking for an operation outside an existing grant must not silently broaden that grant.
        binding.approvalLifetimePicker.setSelection(if (context.permission != null) 0 else 3)
        binding.approvalLifetimePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                binding.nativeRememberNote.setText(if (position == 0) R.string.approval_once_note else R.string.approval_timed_note)
                binding.approvalScopePicker.isEnabled = position != 0
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        var selectedScope: PermissionScope? = null
        var lastScopeChoice = 0
        binding.approvalScopePicker.adapter = ArrayAdapter(this, R.layout.item_identity_choice,
            listOf(R.string.approval_scope_all, R.string.approval_scope_current, R.string.approval_scope_custom).map(::getString))
        binding.approvalScopePicker.setSelection(0)
        binding.approvalScopeSummary.isVisible = false
        binding.approvalScopePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                when (position) {
                    0 -> { selectedScope = null; lastScopeChoice = 0 }
                    1 -> { selectedScope = request.permissionScope(); lastScopeChoice = 1 }
                    2 -> PermissionScopeEditor.show(this@SignerActivity, selectedScope ?: request.permissionScope(),
                        onCancel = { binding.approvalScopePicker.setSelection(lastScopeChoice) }) { scope ->
                        selectedScope = scope
                        lastScopeChoice = 2
                        binding.approvalScopeSummary.isVisible = true
                        binding.approvalScopeSummary.text = PermissionScopeEditor.summary(this@SignerActivity, scope)
                    }
                }
                binding.approvalScopeSummary.text = PermissionScopeEditor.summary(this@SignerActivity, selectedScope)
                binding.approvalScopeSummary.isVisible = selectedScope != null
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // Name the sole identity too, without introducing a choice where there is none.
        // With exactly one, Approve still always means that pairing.
        // Default selection precedence: the request's current_user match, else the caller's
        // existing bound identity, else the first pairing. For an already-approved caller this
        // sheet shows precisely when their current_user could not be honoured silently -- either
        // it named an identity we don't have, or it named one of our *other* pairings (see
        // IntentGate.plan/IdentityRouting.isBoundIdentity) -- so the picker defaulting to the
        // current_user match is exactly the identity the app asked for, and identityRebindHint
        // below compares whatever ends up selected against the binding directly: a user about to
        // rebind the app to a different identity (whether because the app asked or because they
        // moved the picker themselves) sees a clear warning before Approve would do that,
        // rather than relying on the precedence alone to prevent a silent rebind.
        val boundPubkeyHex = context.permission?.boundIdentityPubkeyHex
        binding.identityRow.isVisible = true
        binding.identityPicker.isVisible = pairings.size > 1
        binding.singleIdentityValue.isVisible = pairings.size == 1
        binding.singleIdentityValue.text = pairings.singleOrNull()?.displayLabel()
        binding.identityRebindHint.isVisible = false
        if (pairings.size > 1) {
            binding.identityPicker.adapter = ArrayAdapter(
                this,
                R.layout.item_identity_choice,
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
        // Keep each decision readable as one word on narrow screens or with larger system text.
        val stackDecisions = resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 360
        binding.decisionGroup.orientation = if (stackDecisions) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val gap = (6 * resources.displayMetrics.density).roundToInt()
        listOf(binding.declineButton, binding.approveButton).forEachIndexed { index, button ->
            button.updateLayoutParams<LinearLayout.LayoutParams> {
                width = if (stackDecisions) LinearLayout.LayoutParams.MATCH_PARENT else 0
                weight = if (stackDecisions) 0f else 1f
                marginStart = if (!stackDecisions && index == 1) gap else 0
                marginEnd = if (!stackDecisions && index == 0) gap else 0
                topMargin = if (stackDecisions && index == 1) gap * 2 else 0
            }
        }
        binding.denyAlwaysLink.isVisible = context.nativeCallingPackage != null
        binding.progressGroup.isVisible = false

        binding.approveButton.setOnClickListener {
            requireUnlockedThen {
                if (sheetSequence != requestSequence || !waitingForApproval || isFinishing) return@requireUnlockedThen
                if (batchRecipientIsDenied()) {
                    rejectAndFinish(context)
                    return@requireUnlockedThen
                }
                val chosen = pairings.getOrNull(binding.identityPicker.selectedItemPosition) ?: pairings.first()
                val lifetime = if (context.nativeCallingPackage == null || request is Nip55Request.ProvisionRendezvous) ApprovalLifetime.ONCE
                    else lifetimes[binding.approvalLifetimePicker.selectedItemPosition.coerceIn(lifetimes.indices)]
                val saved = context.nativeCallingPackage?.let {
                    pairingStore.approveIfSheetCurrent(it, chosen, context.permission, lifetime, selectedScope)
                } ?: pairingStore.pairings().any { it == chosen }
                if (!saved) {
                    Toast.makeText(this, R.string.approval_changed, Toast.LENGTH_LONG).show()
                    rejectAndFinish(context)
                } else handle(chosen, request, context.copy(userApproved = true))
            }
        }
        binding.declineButton.setOnClickListener {
            logActivity(request, context, identityLabel = null, outcome = ActivityLogEntry.Outcome.REJECTED_USER)
            rejectAndFinish(context)
        }
        binding.denyAlwaysLink.setOnClickListener {
            requireUnlockedThen {
                if (sheetSequence != requestSequence || !waitingForApproval || isFinishing) return@requireUnlockedThen
                context.nativeCallingPackage?.let { pairingStore.deny(it) }
                logActivity(request, context, identityLabel = null, outcome = ActivityLogEntry.Outcome.REJECTED_USER)
                rejectAndFinish(context)
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
    private fun handle(pairing: Pairing, request: Nip55Request, context: RequestContext) {
        if (request is Nip55Request.ProvisionRendezvous) {
            handleProvision(pairing, request, context)
            return
        }
        if (batchRecipientIsDenied()) {
            rejectAndFinish(context)
            return
        }
        val currentPairing = pairingStore.pairings().firstOrNull { it.signerPubkeyHex == pairing.signerPubkeyHex }
        val pairingUnchanged = currentPairing?.copy(label = null) == pairing.copy(label = null)
        val grant = context.nativeCallingPackage?.let(pairingStore::permission)
        val authorised = context.userApproved || (grant?.allows(request.permissionMethod(), request.permissionEventKind()) == true &&
            IdentityRouting.isBoundIdentity(pairing, grant.boundIdentityPubkeyHex))
        if (!pairingUnchanged || !authorised) {
            if (context.userApproved) {
                if (!silent) Toast.makeText(this, R.string.approval_changed, Toast.LENGTH_LONG).show()
                rejectAndFinish(context)
            } else if (!silent) showApprovalSheet(pairingStore.pairings(), request, context.copy(permission = grant))
            else rejectAndFinish(context)
            return
        }
        waitingForApproval = false
        if (!silent) {
            showRequestInformation(request, context)
            binding.identityRow.isVisible = true
            binding.identityPicker.isVisible = false
            binding.singleIdentityValue.isVisible = true
            binding.singleIdentityValue.text = pairing.displayLabel()
            binding.identityRebindHint.isVisible = false
            binding.permissionsRow.isVisible = false
            binding.webOneShotNote.isVisible = false
            binding.nativeRememberNote.isVisible = false
            binding.decisionGroup.isVisible = false
            binding.denyAlwaysLink.isVisible = false
            binding.nativePermissionControls.isVisible = false
        }

        if (request is Nip55Request.GetPublicKey) {
            // Answered from the pairing record: no relay round trip needed once paired.
            // Native Amber-compatible clients expect npub (see npubWire's doc for the Primal
            // failure a hex answer causes). NIP-55's web transport specifies hex instead.
            val value = if (context.web != null) pairing.signerPubkeyHex else npubWire(pairing.signerPubkeyHex)
            logActivity(request, context, pairing.displayLabel(), ActivityLogEntry.Outcome.SIGNED,
                identityReference = activityLogStore.identityReferenceFor(pairing.signerPubkeyHex))
            respondSuccess(request, value, isPublicKeyRequest = true, context = context)
            return
        }

        if (request is Nip55Request.DecryptZapEvent) {
            handleDecryptZapEvent(pairing, request, context)
            return
        }

        submitAndRespond(pairing, request, context, cacheableFor(request)) { client ->
            when (request) {
                is Nip55Request.ProvisionRendezvous -> error("handled by the one-shot provision path")
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

    private fun handleProvision(pairing: Pairing, request: Nip55Request.ProvisionRendezvous, context: RequestContext) {
        val caller = context.nativeCallingPackage
        if (!context.userApproved || caller == null || context.web != null || batchEnabled ||
            pairing.signerPubkeyHex != request.provision.expectedPersona) {
            rejectAndFinish(context); return
        }
        val sequence = provisionSequence
        val originalConnection = pairing.copy(label = null)
        waitingForApproval = false
        binding.decisionGroup.isVisible = false
        binding.nativePermissionControls.isVisible = false
        binding.progressGroup.isVisible = true
        lifecycleScope.launch {
            val requester = currentCoroutineContext()
            val approval = ProvisionApproval(request.provision) {
                requester.isActive && provisionSequence == sequence && !isFinishing && !isDestroyed &&
                    resultRecipient == caller && pairingStore.permission(caller) == context.permission &&
                    pairingStore.permission(caller)?.state != AppPermissionState.DENIED &&
                    pairingStore.pairings().any { it.copy(label = null) == originalConnection }
            }
            requester.ensureActive()
            val outcome = HeartwoodSession.withClient(pairing, null, HeartwoodRequestPriority.CEREMONY) { client ->
                if (!approval.dispatch(System.currentTimeMillis() / 1000)) HeartwoodResult.Failure(HeartwoodError.InvalidInput("Provision approval is stale"))
                else client.provisionRendezvous(request.provision) { approval.current(System.currentTimeMillis() / 1000) }
            }
            requester.ensureActive()
            when (val result = outcome.result) {
                is HeartwoodResult.Success -> {
                    if (!approval.release(result.value, System.currentTimeMillis() / 1000)) { rejectAndFinish(context); return@launch }
                    // Native ActivityResult only: never clipboard, browser callback, provider or cache.
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, request.id).putExtra(EXTRA_PACKAGE, packageName).putExtra(EXTRA_RESULT, result.value))
                    finish()
                }
                is HeartwoodResult.Failure -> showErrorAndReject(context, result.error)
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
    private fun handleDecryptZapEvent(
        pairing: Pairing,
        request: Nip55Request.DecryptZapEvent,
        context: RequestContext,
    ) {
        val decoded = PrivateZap.decodeAnonTag(request.eventJson)
        val forward = when (decoded) {
            is ZapDecodeResult.Malformed,
            is ZapDecodeResult.NotAZapRequest,
            is ZapDecodeResult.NoAnonTag,
            is ZapDecodeResult.MalformedAnon,
            -> {
                rejectAndFinish(context)
                return
            }
            is ZapDecodeResult.Forward -> decoded
        }

        submitAndRespond(pairing, request, context, PrivateZap.cacheableFor(forward)) { client ->
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
        context: RequestContext,
        cacheable: CacheableDecrypt?,
        operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
    ) {
        if (!silent) {
            binding.progressGroup.isVisible = true
        } else {
            silentBackPressBlock.isEnabled = true
        }
        lifecycleScope.launch {
            val started = System.nanoTime()
            val outcome = HeartwoodSession.withClient(pairing, cacheable, operation = operation)
            logActivity(request, context, pairing.displayLabel(), ActivityLog.outcomeFor(outcome),
                ActivityLog.failureCategoryFor(outcome), (System.nanoTime() - started) / 1_000_000,
                activityLogStore.identityReferenceFor(pairing.signerPubkeyHex))
            when (val result = outcome.result) {
                is HeartwoodResult.Success -> respondSuccess(
                    request,
                    result.value,
                    isPublicKeyRequest = false,
                    context = context,
                )
                is HeartwoodResult.Failure -> showErrorAndReject(context, result.error)
            }
        }
    }

    private fun respondSuccess(
        request: Nip55Request,
        value: String,
        isPublicKeyRequest: Boolean,
        context: RequestContext,
    ) {
        val web = context.web
        if (web != null) {
            when (val result = WebNip55ResultBuilder.build(web, request, value)) {
                is WebResult.Failure -> {
                    Toast.makeText(this, R.string.error_invalid_signer_response, Toast.LENGTH_LONG).show()
                    rejectAndFinish(context)
                }
                is WebResult.Success -> deliverWebResult(result.action)
            }
            return
        }

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
        val batchValue = NativeBatchResultBuilder.success(request, value, packageName)
        if (batchEnabled && batchValue == null) {
            Toast.makeText(this, R.string.error_invalid_signer_response, Toast.LENGTH_LONG).show()
            rejectAndFinish(context)
            return
        }
        completeNativeRequest(context, data, batchValue ?: NativeSignerResult(packageName, request.id, value))
    }

    private fun deliverWebResult(action: WebResultAction) {
        when (action) {
            is WebResultAction.CopyToClipboard -> {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.web_result_clip_label), action.text))
                Toast.makeText(this, R.string.web_result_copied, Toast.LENGTH_LONG).show()
                finish()
            }
            is WebResultAction.OpenCallback -> {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                    )
                    finish()
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.error_web_callback_unavailable, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun showErrorAndReject(context: RequestContext, error: HeartwoodError) {
        // Silent means invisible end to end: an already-approved caller must never see a Toast
        // either, matching the "no UI at all" contract.
        if (!silent) {
            val message = when (error) {
                HeartwoodError.NotConnected -> getString(R.string.error_not_paired)
                HeartwoodError.Busy -> getString(R.string.error_busy)
                HeartwoodError.Timeout -> getString(R.string.error_timeout)
                is HeartwoodError.InvalidInput -> error.message
                is HeartwoodError.Protocol -> error.message
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        rejectAndFinish(context)
    }

    private fun rejectAndFinish(context: RequestContext) {
        // NIP-55 defines a success callback/clipboard value but no rejection payload. Returning
        // nothing leaves the website's request unfulfilled without disclosing extra detail.
        if (context.web != null) {
            finish()
            return
        }
        val data = Intent().apply {
            putExtra(EXTRA_REJECTED, true)
            putExtra(EXTRA_ID, context.incoming.raw.id)
        }
        completeNativeRequest(context, data, NativeSignerResult(packageName, context.incoming.raw.id, rejected = true))
    }

    private fun completeNativeRequest(context: RequestContext, singleData: Intent, result: NativeSignerResult) {
        if (!batchEnabled || context.web != null) {
            setResult(RESULT_OK, singleData)
            finish()
            return
        }
        if (!batchResults.append(result)) {
            batchResults.append(NativeSignerResult(packageName, result.id, rejected = true, unavailable = true))
            Toast.makeText(this, R.string.batch_overflow, Toast.LENGTH_LONG).show()
        }
        requestInProgress = false
        waitingForApproval = false
        activeBatchRequest = null
        silentBackPressBlock.isEnabled = false
        if (pendingNativeIntents.isNotEmpty()) {
            processNextNativeIntent()
        } else {
            // Allow already-posted SINGLE_TOP intents to join a just-completed fast local reply.
            finishBatchJob = lifecycleScope.launch {
                delay(200)
                if (!requestInProgress && pendingNativeIntents.isEmpty()) {
                    singleData.putExtra("results", batchResults.toJson())
                    setResult(RESULT_OK, singleData)
                    finish()
                }
            }
        }
    }

    private fun methodLabel(request: Nip55Request): String = getString(
        when (request) {
            is Nip55Request.ProvisionRendezvous -> R.string.method_provision_rendezvous
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

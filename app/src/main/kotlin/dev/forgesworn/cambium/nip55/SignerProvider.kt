package dev.forgesworn.cambium.nip55

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import dev.forgesworn.cambium.log.ActivityLog
import dev.forgesworn.cambium.log.ActivityLogEntry
import dev.forgesworn.cambium.log.ActivityLogStore
import dev.forgesworn.cambium.nip57.PrivateZap
import dev.forgesworn.cambium.nip57.ZapDecodeResult
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.pairing.IdentityRouting
import dev.forgesworn.cambium.pairing.Pairing
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.signer.CacheableDecrypt
import dev.forgesworn.cambium.signer.HeartwoodClient
import dev.forgesworn.cambium.signer.HeartwoodOutcome
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.HeartwoodSession
import dev.forgesworn.cambium.signer.displayLabel
import dev.forgesworn.cambium.signer.isDeterministicDecryptFailure
import dev.forgesworn.cambium.signer.isPolicyRefusal
import kotlinx.coroutines.runBlocking

/**
 * The NIP-55 "silent" path: clients query this content provider before falling back to the
 * visible [SignerActivity] intent. A live test against a real Heartwood showed Amethyst queries
 * this provider for *every* operation once an app is approved, not just get_public_key, and can
 * burst around ten concurrent queries while the user is typing a reply (drafts plus decrypts).
 * SIGN_EVENT, NIP04_*, and NIP44_* forward to [HeartwoodSession]'s single admission-controlled
 * worker from inside `query()` -- acceptable because these clients call `query()` from a
 * background thread, never the main one. See [HeartwoodSession]'s class doc for why the request
 * gate and no-cancellation rule exist: an earlier, simpler design let a caller's own timeout
 * cancel an in-flight rust-nostr call, which is suspected to have wedged the whole process once
 * under load.
 *
 * `GET_PUBLIC_KEY` is still declared for discovery but answers `null` for anyone the user has
 * not denied: both Amber and Primal force login through the visible intent rather than the
 * silent path, and Cambium matches that rather than the more permissive reading of the NIP-55
 * text. A *denied* caller gets the terminal `rejected` answer even here, so a blocked app's
 * login probe cannot keep bouncing through the invisible intent path.
 *
 * `DECRYPT_ZAP_EVENT` decodes the DIP-03 "private zap" `anon` tag locally (see
 * [dev.forgesworn.cambium.nip57.PrivateZap]) and forwards the result as an ordinary
 * nip04_decrypt. A public zap (no `anon` tag) or a malformed one answers `rejected` immediately,
 * without a relay round trip. Declaring this authority also silenced "Failed to find provider
 * info" errors Amethyst logged on every zap when it was undeclared.
 *
 * `PING` answers directly for an already-approved, paired caller ("pong"), so a client can cheaply
 * check "is Cambium here and willing to talk to me" without a relay round trip.
 *
 * SIGN_EVENT additionally declines NIP-37 draft events (kind [NIP37_DRAFT_KIND]) immediately,
 * without forwarding or even joining the queue: Amethyst auto-saves a draft roughly every 2s while
 * the user types, and forwarding each one to a 1-2s hardware round trip is what buried real
 * requests behind a flood of drafts in testing.
 *
 * An explicit policy refusal from Heartwood (see [dev.forgesworn.cambium.signer.isPolicyRefusal])
 * or a deterministic decrypt failure (see
 * [dev.forgesworn.cambium.signer.isDeterministicDecryptFailure]) answers a `rejected` cursor
 * rather than `null`, so a client stops escalating a blocked or unrecoverable request to the
 * visible flow every couple of seconds. Everything else that cannot be answered here -- an
 * unapproved/unpaired caller, a missing argument, the worker queue being full, a timeout, or any
 * other failure -- answers `null` (defer to the intent). The caller is always taken from
 * [getCallingPackage], never from query arguments -- a caller cannot claim to be someone else by
 * passing a different package name in. Forwarding arguments arrive in the `projection` array as
 * `[payload, otherPubkey, currentUser]` -- that is how Amber's real clients pass them, despite the
 * NIP-55 text describing `selectionArgs`.
 *
 * `currentUser` (index 2) is resolved to a pairing via [IdentityRouting.resolve] in
 * [requirePairing], same precedence as the intent path: an explicit `current_user` wins if it
 * names an identity we have, else the caller's bound identity, else the sole pairing. A
 * `current_user` naming an identity we don't have, or no identity resolving at all (more than one
 * pairing, no `current_user`, no binding -- should not normally happen for an approved caller,
 * since approving always binds), both answer `null` here -- there is no way to ask which identity
 * was meant from the silent path, so it defers to the intent rather than guessing.
 *
 * NIP04_DECRYPT and NIP44_DECRYPT results (successes and deterministic failures alike) are cached
 * by [HeartwoodSession] itself -- see its class doc -- since Amethyst was observed re-requesting
 * the same decrypt repeatedly while browsing, including legacy content that will never decrypt.
 *
 * A caller with a *remembered* denial (see [PairingStore.deny], set from the approval sheet's
 * "always deny" link) gets `rejected` immediately, for every authority, without ever resolving a
 * pairing or touching the queue -- distinct from a caller with no remembered choice yet, who gets
 * `null` ("try the intent", where they will see the approval sheet). See [resolveCaller].
 *
 * [forward] logs to [ActivityLogStore] on every *definitive, non-cached* outcome (a real result,
 * or a `rejected` cursor) -- never on a `null` deferral, since those are transient/retried via the
 * intent path, which will log its own outcome once the request actually concludes there; logging
 * both would double-count what the user experienced as one request. A [HeartwoodOutcome.Cached]
 * hit is not logged either (see [logActivityUnlessCached]): it can repeat many times a second
 * during a burst, and would be pure noise against the capped log rather than signal. Nothing else
 * in this class logs: [queryPing], the NIP-37 draft decline, and [PrivateZap.decodeAnonTag]'s
 * routine "not a private zap" outcomes would mostly add noise (Amethyst pings and drafts
 * constantly) rather than signal about what Cambium actually did.
 */
class SignerProvider : ContentProvider() {

    private lateinit var pairingStore: PairingStore
    private lateinit var activityLogStore: ActivityLogStore

    override fun onCreate(): Boolean {
        val ctx = requireNotNull(context)
        pairingStore = PairingStore(ctx)
        activityLogStore = ActivityLogStore.getInstance(ctx)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = when (uri.authority) {
        PING_AUTHORITY -> queryPing()

        SIGN_EVENT_AUTHORITY -> querySignEvent(projection)

        NIP04_ENCRYPT_AUTHORITY -> queryForward(
            projection,
            Nip55Request.TYPE_NIP04_ENCRYPT,
            requiresOtherPubkey = true,
        ) { client, payload, otherPubkey ->
            client.nip04Encrypt(otherPubkey, payload)
        }

        NIP04_DECRYPT_AUTHORITY -> queryForward(
            projection,
            Nip55Request.TYPE_NIP04_DECRYPT,
            requiresOtherPubkey = true,
            cacheMethod = CacheableDecrypt.Method.NIP04,
        ) { client, payload, otherPubkey ->
            client.nip04Decrypt(otherPubkey, payload)
        }

        NIP44_ENCRYPT_AUTHORITY -> queryForward(
            projection,
            Nip55Request.TYPE_NIP44_ENCRYPT,
            requiresOtherPubkey = true,
        ) { client, payload, otherPubkey ->
            client.nip44Encrypt(otherPubkey, payload)
        }

        NIP44_DECRYPT_AUTHORITY -> queryForward(
            projection,
            Nip55Request.TYPE_NIP44_DECRYPT,
            requiresOtherPubkey = true,
            cacheMethod = CacheableDecrypt.Method.NIP44,
        ) { client, payload, otherPubkey ->
            client.nip44Decrypt(otherPubkey, payload)
        }

        DECRYPT_ZAP_EVENT_AUTHORITY -> queryDecryptZapEvent(projection)

        // GET_PUBLIC_KEY: an approved (or not-yet-decided) caller always gets `null` -- login
        // goes through the visible intent, see the class doc -- but a *denied* caller still gets
        // the terminal `rejected` answer here, like every other authority, so a blocked app's
        // login probe cannot keep bouncing through the invisible intent path.
        GET_PUBLIC_KEY_AUTHORITY -> withApprovedCaller { _, _ -> null }

        else -> null
    }

    private fun queryPing(): Cursor? = withApprovedCaller { _, _ ->
        if (!pairingStore.isPaired()) return@withApprovedCaller null
        MatrixCursor(arrayOf(COLUMN_RESULT)).apply { addRow(arrayOf(PONG)) }
    }

    private fun querySignEvent(projection: Array<out String>?): Cursor? = withApprovedCaller { caller, boundIdentity ->
        val pairing = requirePairing(caller, boundIdentity, projection?.getOrNull(2)) ?: return@withApprovedCaller null
        val payload = requirePayload(caller, projection) ?: return@withApprovedCaller null

        val eventKind = extractEventKind(payload)
        if (eventKind == NIP37_DRAFT_KIND) {
            Log.i(TAG, "silent sign_event from $caller declined: draft (kind $NIP37_DRAFT_KIND)")
            return@withApprovedCaller rejectedCursor()
        }

        forward(
            caller,
            pairing,
            Nip55Request.TYPE_SIGN_EVENT,
            eventKind,
            payload,
            otherPubkey = "",
            includeEventAndSignature = true,
            cacheable = null, // signs are never cached
        ) { client, p, _ -> client.signEvent(p) }
    }

    /**
     * `decrypt_zap_event`: [PrivateZap.decodeAnonTag] runs locally first (no relay call, no queue
     * slot) to turn the zap request's `anon` tag into an ordinary nip04_decrypt call. Anything
     * that isn't a decryptable private zap -- wrong kind, no `anon` tag (an ordinary public zap),
     * a malformed `anon` tag, or a structurally broken event -- answers `rejected` immediately;
     * all are deterministic, local problems that a relay round trip cannot fix. On a `Forward`,
     * [PrivateZap.decryptAndValidate] and [PrivateZap.cacheableFor] are the single shared home for
     * the decrypt-then-check-kind-9733 call and its cache key -- `SignerActivity.handleDecryptZapEvent`
     * uses the exact same two calls -- so a plaintext that fails the kind-9733 check flows through
     * the existing deterministic-failure/caching logic in [forward] without any special casing
     * here.
     */
    private fun queryDecryptZapEvent(projection: Array<out String>?): Cursor? = withApprovedCaller { caller, boundIdentity ->
        val pairing = requirePairing(caller, boundIdentity, projection?.getOrNull(2)) ?: return@withApprovedCaller null
        val eventJson = requirePayload(caller, projection) ?: return@withApprovedCaller null

        when (val decoded = PrivateZap.decodeAnonTag(eventJson)) {
            is ZapDecodeResult.Malformed -> {
                Log.i(TAG, "silent decrypt_zap_event from $caller declined: ${decoded.reason}")
                rejectedCursor()
            }
            is ZapDecodeResult.NotAZapRequest -> {
                Log.i(TAG, "silent decrypt_zap_event from $caller declined: not a kind 9734 event")
                rejectedCursor()
            }
            is ZapDecodeResult.NoAnonTag -> {
                Log.i(TAG, "silent decrypt_zap_event from $caller declined: public zap (no anon tag)")
                rejectedCursor()
            }
            is ZapDecodeResult.MalformedAnon -> {
                Log.i(TAG, "silent decrypt_zap_event from $caller declined: ${decoded.reason}")
                rejectedCursor()
            }
            is ZapDecodeResult.Forward -> forward(
                caller,
                pairing,
                Nip55Request.TYPE_DECRYPT_ZAP_EVENT,
                eventKind = null,
                decoded.nip04Payload,
                decoded.counterpartyPubkeyHex,
                includeEventAndSignature = false,
                cacheable = PrivateZap.cacheableFor(decoded),
            ) { client, _, _ -> PrivateZap.decryptAndValidate(client, decoded) }
        }
    }

    /**
     * Shared path for the four NIP04/NIP44 authorities: resolves the caller and pairing, requires
     * a payload and (for these methods) an other-party pubkey, then forwards. [cacheMethod] is
     * non-null only for the two decrypt authorities -- decryption is deterministic and safe to
     * cache; encryption (nonce freshness) is not, so it stays `null` there.
     */
    private fun queryForward(
        projection: Array<out String>?,
        method: String,
        requiresOtherPubkey: Boolean,
        cacheMethod: CacheableDecrypt.Method? = null,
        call: suspend (HeartwoodClient, payload: String, otherPubkey: String) -> HeartwoodResult<String>,
    ): Cursor? = withApprovedCaller { caller, boundIdentity ->
        val pairing = requirePairing(caller, boundIdentity, projection?.getOrNull(2)) ?: return@withApprovedCaller null
        val payload = requirePayload(caller, projection) ?: return@withApprovedCaller null

        val otherPubkey = projection?.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (requiresOtherPubkey && otherPubkey == null) {
            Log.w(TAG, "silent query from $caller missing other-party pubkey")
            return@withApprovedCaller null
        }

        val cacheable = cacheMethod?.let { CacheableDecrypt(it, otherPubkey.orEmpty(), payload) }
        forward(caller, pairing, method, eventKind = null, payload, otherPubkey.orEmpty(), includeEventAndSignature = false, cacheable, call)
    }

    private sealed interface CallerResolution {
        data class Approved(val packageName: String, val boundIdentityPubkeyHex: String?) : CallerResolution
        data object Denied : CallerResolution
        data object Unresolved : CallerResolution
    }

    private fun resolveCaller(): CallerResolution {
        val caller = callingPackage
        if (caller == null) {
            Log.w(TAG, "silent query refused: calling package unresolvable")
            return CallerResolution.Unresolved
        }
        return when (val permission = pairingStore.permission(caller)) {
            null -> {
                Log.i(TAG, "silent query from unapproved caller $caller; deferring to intent")
                CallerResolution.Unresolved
            }
            else -> when (permission.state) {
                AppPermissionState.APPROVED -> CallerResolution.Approved(caller, permission.boundIdentityPubkeyHex)
                AppPermissionState.DENIED -> {
                    Log.i(TAG, "silent query from denied caller $caller; answering rejected")
                    CallerResolution.Denied
                }
            }
        }
    }

    /** Runs [block] for an approved caller, passing its bound identity along; answers `rejected`
     * immediately for a denied one (no [block] call at all); defers to the intent (`null`) for a
     * caller with no remembered choice yet, or one that could not be resolved. */
    private inline fun withApprovedCaller(block: (caller: String, boundIdentityPubkeyHex: String?) -> Cursor?): Cursor? =
        when (val resolution = resolveCaller()) {
            is CallerResolution.Approved -> block(resolution.packageName, resolution.boundIdentityPubkeyHex)
            CallerResolution.Denied -> rejectedCursor()
            CallerResolution.Unresolved -> null
        }

    /** Resolves which pairing this request should route to -- see the class doc's `currentUser`
     * paragraph. `null` (defer to intent) covers every case that can't be answered silently:
     * nothing paired, a `current_user` naming an identity we don't have, or no identity resolving
     * at all. */
    private fun requirePairing(caller: String, boundIdentityPubkeyHex: String?, rawCurrentUser: String?): Pairing? {
        val pairings = pairingStore.pairings()
        if (pairings.isEmpty()) {
            Log.w(TAG, "silent query from $caller but nothing paired")
            return null
        }
        return when (val routed = IdentityRouting.resolve(rawCurrentUser, boundIdentityPubkeyHex, pairings)) {
            is IdentityRouting.Result.Resolved -> routed.pairing
            IdentityRouting.Result.UnknownCurrentUser -> {
                Log.w(TAG, "silent query from $caller named a current_user we don't have; deferring to intent")
                null
            }
            IdentityRouting.Result.Ambiguous -> {
                Log.w(TAG, "silent query from $caller ambiguous across ${pairings.size} pairings; deferring to intent")
                null
            }
        }
    }

    private fun requirePayload(caller: String, projection: Array<out String>?): String? {
        val payload = projection?.getOrNull(0)?.takeIf { it.isNotBlank() }
        if (payload == null) {
            Log.w(TAG, "silent query from $caller with no payload")
        }
        return payload
    }

    /**
     * Submits to [HeartwoodSession]'s cache/admission-controlled worker and blocks this binder
     * thread for the result -- unless [cacheable] is a hit, in which case [HeartwoodSession]
     * answers before ever touching the queue. Returns `null` if the worker's queue is already
     * full, the call times out, or fails for a reason other than an explicit policy refusal or a
     * deterministic decrypt failure (see class doc). Logs to [ActivityLogStore] on both
     * definitive outcomes (see the class doc's logging paragraph) -- never on the `null` deferral.
     */
    private fun forward(
        caller: String,
        pairing: Pairing,
        method: String,
        eventKind: Int?,
        payload: String,
        otherPubkey: String,
        includeEventAndSignature: Boolean,
        cacheable: CacheableDecrypt?,
        call: suspend (HeartwoodClient, String, String) -> HeartwoodResult<String>,
    ): Cursor? {
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = runBlocking {
            HeartwoodSession.trySilent(pairing, cacheable) { client -> call(client, payload, otherPubkey) }
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt

        if (outcome == null) {
            Log.w(TAG, "silent forward for $caller refused (queue full) or timed out after ${elapsed}ms; deferring to intent")
            return null
        }

        val result = outcome.result
        return when (result) {
            is HeartwoodResult.Success -> {
                Log.i(TAG, "silent forward for $caller answered in ${elapsed}ms")
                logActivityUnlessCached(caller, method, eventKind, pairing, outcome)
                buildResultCursor(result.value, includeEventAndSignature)
            }
            is HeartwoodResult.Failure -> {
                if (isPolicyRefusal(result.error) || isDeterministicDecryptFailure(result.error)) {
                    Log.i(TAG, "silent forward for $caller refused after ${elapsed}ms (${result.error}); answering rejected")
                    logActivityUnlessCached(caller, method, eventKind, pairing, outcome)
                    rejectedCursor()
                } else {
                    Log.w(TAG, "silent forward for $caller failed after ${elapsed}ms (${result.error}); deferring to intent")
                    null
                }
            }
        }
    }

    /** A [HeartwoodOutcome.Cached] hit on the silent path can repeat many times a second during a
     * burst (Amethyst re-requesting the same decrypt while the user types) -- logging every one
     * would be pure noise against the capped 500-entry log and needless work on the hot path the
     * cache exists to keep fast. The intent path (`SignerActivity`) logs cache hits normally: it
     * is a one-off, user-visible flow, not a background burst, so `SIGNED` vs
     * `ANSWERED_FROM_CACHE` stays meaningful signal there. */
    private fun logActivityUnlessCached(caller: String, method: String, eventKind: Int?, pairing: Pairing, outcome: HeartwoodOutcome<String>) {
        if (outcome is HeartwoodOutcome.Cached) return
        logActivity(caller, method, eventKind, pairing, ActivityLog.outcomeFor(outcome))
    }

    private fun logActivity(caller: String, method: String, eventKind: Int?, pairing: Pairing, outcome: ActivityLogEntry.Outcome) {
        activityLogStore.append(
            ActivityLogEntry(
                timestampMillis = System.currentTimeMillis(),
                callingPackage = caller,
                method = method,
                eventKind = eventKind,
                identityLabel = pairing.displayLabel(),
                outcome = outcome,
            )
        )
    }

    private fun rejectedCursor(): Cursor = MatrixCursor(arrayOf(COLUMN_REJECTED)).apply {
        addRow(arrayOf("true"))
    }

    private fun buildResultCursor(value: String, includeEventAndSignature: Boolean): Cursor {
        if (!includeEventAndSignature) {
            return MatrixCursor(arrayOf(COLUMN_RESULT)).apply { addRow(arrayOf(value)) }
        }
        return MatrixCursor(arrayOf(COLUMN_RESULT, COLUMN_EVENT, COLUMN_SIGNATURE)).apply {
            addRow(arrayOf(value, value, extractEventSignatureHex(value) ?: value))
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "CambiumProvider"
        private const val COLUMN_RESULT = "result"
        private const val COLUMN_EVENT = "event"
        private const val COLUMN_SIGNATURE = "signature"
        private const val COLUMN_REJECTED = "rejected"
        private const val PONG = "pong"

        // NIP-37 draft events. Amethyst auto-saves a draft roughly every 2s while the user is
        // typing a reply; forwarding each one to a 1-2s hardware round trip is what buried real
        // requests behind a flood of drafts in testing. Declined silently and permanently --
        // clients treat the `rejected` column as terminal, no intent fallback -- rather than
        // forwarded. Hardcoded on for now; a settings toggle can replace this later.
        private const val NIP37_DRAFT_KIND = 31234

        const val GET_PUBLIC_KEY_AUTHORITY = "dev.forgesworn.cambium.GET_PUBLIC_KEY"
        const val PING_AUTHORITY = "dev.forgesworn.cambium.PING"
        const val SIGN_EVENT_AUTHORITY = "dev.forgesworn.cambium.SIGN_EVENT"
        const val NIP04_ENCRYPT_AUTHORITY = "dev.forgesworn.cambium.NIP04_ENCRYPT"
        const val NIP04_DECRYPT_AUTHORITY = "dev.forgesworn.cambium.NIP04_DECRYPT"
        const val NIP44_ENCRYPT_AUTHORITY = "dev.forgesworn.cambium.NIP44_ENCRYPT"
        const val NIP44_DECRYPT_AUTHORITY = "dev.forgesworn.cambium.NIP44_DECRYPT"
        const val DECRYPT_ZAP_EVENT_AUTHORITY = "dev.forgesworn.cambium.DECRYPT_ZAP_EVENT"
    }
}

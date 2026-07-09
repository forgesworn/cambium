package dev.forgesworn.cambium.nip55

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import dev.forgesworn.cambium.nip57.PrivateZap
import dev.forgesworn.cambium.nip57.ZapDecodeResult
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.pairing.Pairing
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.signer.CacheableDecrypt
import dev.forgesworn.cambium.signer.HeartwoodClient
import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.HeartwoodSession
import dev.forgesworn.cambium.signer.isDeterministicDecryptFailure
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
 * An explicit policy refusal from Heartwood (error text containing a refusal keyword -- see
 * [REFUSAL_KEYWORDS]) or a deterministic decrypt failure (see
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
 * NIP04_DECRYPT and NIP44_DECRYPT results (successes and deterministic failures alike) are cached
 * by [HeartwoodSession] itself -- see its class doc -- since Amethyst was observed re-requesting
 * the same decrypt repeatedly while browsing, including legacy content that will never decrypt.
 *
 * A caller with a *remembered* denial (see [PairingStore.deny], set from the approval sheet's
 * "always deny" link) gets `rejected` immediately, for every authority, without ever resolving a
 * pairing or touching the queue -- distinct from a caller with no remembered choice yet, who gets
 * `null` ("try the intent", where they will see the approval sheet). See [resolveCaller].
 */
class SignerProvider : ContentProvider() {

    private lateinit var pairingStore: PairingStore

    override fun onCreate(): Boolean {
        pairingStore = PairingStore(requireNotNull(context))
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

        NIP04_ENCRYPT_AUTHORITY -> queryForward(projection, requiresOtherPubkey = true) { client, payload, otherPubkey ->
            client.nip04Encrypt(otherPubkey, payload)
        }

        NIP04_DECRYPT_AUTHORITY -> queryForward(
            projection,
            requiresOtherPubkey = true,
            cacheMethod = CacheableDecrypt.Method.NIP04,
        ) { client, payload, otherPubkey ->
            client.nip04Decrypt(otherPubkey, payload)
        }

        NIP44_ENCRYPT_AUTHORITY -> queryForward(projection, requiresOtherPubkey = true) { client, payload, otherPubkey ->
            client.nip44Encrypt(otherPubkey, payload)
        }

        NIP44_DECRYPT_AUTHORITY -> queryForward(
            projection,
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
        GET_PUBLIC_KEY_AUTHORITY -> withApprovedCaller { null }

        else -> null
    }

    private fun queryPing(): Cursor? = withApprovedCaller {
        if (!pairingStore.isPaired()) return@withApprovedCaller null
        MatrixCursor(arrayOf(COLUMN_RESULT)).apply { addRow(arrayOf(PONG)) }
    }

    private fun querySignEvent(projection: Array<out String>?): Cursor? = withApprovedCaller { caller ->
        val pairing = requirePairing(caller) ?: return@withApprovedCaller null
        val payload = requirePayload(caller, projection) ?: return@withApprovedCaller null

        if (extractEventKind(payload) == NIP37_DRAFT_KIND) {
            Log.i(TAG, "silent sign_event from $caller declined: draft (kind $NIP37_DRAFT_KIND)")
            return@withApprovedCaller rejectedCursor()
        }

        forward(
            caller,
            pairing,
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
    private fun queryDecryptZapEvent(projection: Array<out String>?): Cursor? = withApprovedCaller { caller ->
        val pairing = requirePairing(caller) ?: return@withApprovedCaller null
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
        requiresOtherPubkey: Boolean,
        cacheMethod: CacheableDecrypt.Method? = null,
        call: suspend (HeartwoodClient, payload: String, otherPubkey: String) -> HeartwoodResult<String>,
    ): Cursor? = withApprovedCaller { caller ->
        val pairing = requirePairing(caller) ?: return@withApprovedCaller null
        val payload = requirePayload(caller, projection) ?: return@withApprovedCaller null

        val otherPubkey = projection?.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (requiresOtherPubkey && otherPubkey == null) {
            Log.w(TAG, "silent query from $caller missing other-party pubkey")
            return@withApprovedCaller null
        }

        val cacheable = cacheMethod?.let { CacheableDecrypt(it, otherPubkey.orEmpty(), payload) }
        forward(caller, pairing, payload, otherPubkey.orEmpty(), includeEventAndSignature = false, cacheable, call)
    }

    private sealed interface CallerResolution {
        data class Approved(val packageName: String) : CallerResolution
        data object Denied : CallerResolution
        data object Unresolved : CallerResolution
    }

    private fun resolveCaller(): CallerResolution {
        val caller = callingPackage
        if (caller == null) {
            Log.w(TAG, "silent query refused: calling package unresolvable")
            return CallerResolution.Unresolved
        }
        return when (pairingStore.permissionState(caller)) {
            AppPermissionState.APPROVED -> CallerResolution.Approved(caller)
            AppPermissionState.DENIED -> {
                Log.i(TAG, "silent query from denied caller $caller; answering rejected")
                CallerResolution.Denied
            }
            null -> {
                Log.i(TAG, "silent query from unapproved caller $caller; deferring to intent")
                CallerResolution.Unresolved
            }
        }
    }

    /** Runs [block] for an approved caller; answers `rejected` immediately for a denied one
     * (no [block] call at all); defers to the intent (`null`) for a caller with no remembered
     * choice yet, or one that could not be resolved. */
    private inline fun withApprovedCaller(block: (String) -> Cursor?): Cursor? =
        when (val resolution = resolveCaller()) {
            is CallerResolution.Approved -> block(resolution.packageName)
            CallerResolution.Denied -> rejectedCursor()
            CallerResolution.Unresolved -> null
        }

    private fun requirePairing(caller: String): Pairing? {
        val pairing = pairingStore.current()
        if (pairing == null) {
            Log.w(TAG, "silent query from $caller but no pairing stored")
        }
        return pairing
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
     * deterministic decrypt failure (see class doc).
     */
    private fun forward(
        caller: String,
        pairing: Pairing,
        payload: String,
        otherPubkey: String,
        includeEventAndSignature: Boolean,
        cacheable: CacheableDecrypt?,
        call: suspend (HeartwoodClient, String, String) -> HeartwoodResult<String>,
    ): Cursor? {
        val startedAt = SystemClock.elapsedRealtime()
        val result = runBlocking {
            HeartwoodSession.trySilent(pairing, cacheable) { client -> call(client, payload, otherPubkey) }
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt

        if (result == null) {
            Log.w(TAG, "silent forward for $caller refused (queue full) or timed out after ${elapsed}ms; deferring to intent")
            return null
        }

        return when (result) {
            is HeartwoodResult.Success -> {
                Log.i(TAG, "silent forward for $caller answered in ${elapsed}ms")
                buildResultCursor(result.value, includeEventAndSignature)
            }
            is HeartwoodResult.Failure -> {
                if (isPolicyRefusal(result.error) || isDeterministicDecryptFailure(result.error)) {
                    Log.i(TAG, "silent forward for $caller refused after ${elapsed}ms (${result.error}); answering rejected")
                    rejectedCursor()
                } else {
                    Log.w(TAG, "silent forward for $caller failed after ${elapsed}ms (${result.error}); deferring to intent")
                    null
                }
            }
        }
    }

    private fun isPolicyRefusal(error: HeartwoodError): Boolean {
        val message = (error as? HeartwoodError.Protocol)?.message ?: return false
        val lower = message.lowercase()
        return REFUSAL_KEYWORDS.any { it in lower }
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

        // Verified against firmware source (nip46_handler.rs): unbound clients and policy blocks
        // answer "unauthorised"; a physical-button decline answers "user denied". A "timeout"
        // (button not pressed in time) deliberately stays null: the visible retry gives the
        // user another chance to press it.
        private val REFUSAL_KEYWORDS = listOf("unauthorised", "unauthorized", "not allowed", "refused", "denied")

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

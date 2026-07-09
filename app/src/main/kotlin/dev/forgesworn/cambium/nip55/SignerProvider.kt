package dev.forgesworn.cambium.nip55

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.signer.HeartwoodClient
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.HeartwoodSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The NIP-55 "silent" path: clients query this content provider before falling back to the
 * visible [SignerActivity] intent. A live test against a real Heartwood showed that Amethyst
 * queries this provider for *every* operation once an app is approved, not just get_public_key --
 * the earlier "always null except get_public_key" MVP produced hundreds of visible signer popups,
 * one per operation. SIGN_EVENT, NIP04_*, and NIP44_* now forward to the paired Heartwood from
 * inside `query()`, blocking the caller's binder thread for up to [FORWARD_TIMEOUT_MILLIS] --
 * acceptable because NIP-55 clients call `query()` from a background thread, never the main one.
 *
 * `GET_PUBLIC_KEY` is still declared for discovery but always answers `null`: both Amber and
 * Primal force login through the visible intent rather than the silent path, and Cambium matches
 * that rather than the more permissive reading of the NIP-55 text.
 *
 * `DECRYPT_ZAP_EVENT` is declared -- its absence spammed "Failed to find provider info" errors on
 * every zap in Amethyst's feed -- but always answers `null`. Amber implements this as its own
 * decode: unwrapping the zap request event embedded in a zap receipt and decrypting fields inside
 * it, not a plain nip44_decrypt of the payload against the other-party pubkey. Doing that naively
 * would decrypt the wrong thing, so this MVP does not implement it (known gap, see CLAUDE.md);
 * declaring the authority only silences the discovery error and falls back to the intent, which
 * Cambium also does not yet handle as a distinct method (see [Nip55Request]).
 *
 * `PING` answers directly for an already-approved, paired caller ("pong"), so a client can cheaply
 * check "is Cambium here and willing to talk to me" without a relay round trip.
 *
 * The caller is always taken from [getCallingPackage], never from query arguments -- a caller
 * cannot claim to be someone else by passing a different package name in. Forwarding arguments
 * arrive in the `projection` array as `[payload, otherPubkey, currentUser]` -- that is how Amber's
 * real clients pass them, despite the NIP-55 text describing `selectionArgs`.
 *
 * Known gap: an unapproved caller and a permanently-*rejected* caller both get `null` today ("try
 * the intent"); there is no persistent deny-list yet to return a `rejected` cursor column for the
 * latter (see PairingStore).
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

        SIGN_EVENT_AUTHORITY -> queryForward(projection, includeEventAndSignature = true) { client, payload, _ ->
            client.signEvent(payload)
        }

        NIP04_ENCRYPT_AUTHORITY -> queryForward(projection, requiresOtherPubkey = true) { client, payload, otherPubkey ->
            client.nip04Encrypt(otherPubkey, payload)
        }

        NIP04_DECRYPT_AUTHORITY -> queryForward(projection, requiresOtherPubkey = true) { client, payload, otherPubkey ->
            client.nip04Decrypt(otherPubkey, payload)
        }

        NIP44_ENCRYPT_AUTHORITY -> queryForward(projection, requiresOtherPubkey = true) { client, payload, otherPubkey ->
            client.nip44Encrypt(otherPubkey, payload)
        }

        NIP44_DECRYPT_AUTHORITY -> queryForward(projection, requiresOtherPubkey = true) { client, payload, otherPubkey ->
            client.nip44Decrypt(otherPubkey, payload)
        }

        // GET_PUBLIC_KEY: always the intent (see class doc).
        // DECRYPT_ZAP_EVENT: declared to silence discovery errors; not implemented (see class doc).
        else -> null
    }

    private fun queryPing(): Cursor? {
        val caller = callingPackage ?: return null
        if (!pairingStore.isApproved(caller)) return null
        if (!pairingStore.isPaired()) return null

        return MatrixCursor(arrayOf(COLUMN_RESULT)).apply {
            addRow(arrayOf(PONG))
        }
    }

    /**
     * Forwards to the shared [HeartwoodSession], blocking this binder thread for up to
     * [FORWARD_TIMEOUT_MILLIS]. Returns `null` (client falls back to the intent) for an
     * unapproved/unpaired caller, a missing required argument, a timeout, or any
     * [HeartwoodResult.Failure] -- this MVP does not distinguish those cases for the caller.
     */
    private fun queryForward(
        projection: Array<out String>?,
        includeEventAndSignature: Boolean = false,
        requiresOtherPubkey: Boolean = false,
        call: suspend (HeartwoodClient, payload: String, otherPubkey: String) -> HeartwoodResult<String>,
    ): Cursor? {
        val caller = callingPackage ?: return null
        if (!pairingStore.isApproved(caller)) return null
        val pairing = pairingStore.current() ?: return null

        val payload = projection?.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val otherPubkey = projection?.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (requiresOtherPubkey && otherPubkey == null) return null

        val result = runBlocking {
            withTimeoutOrNull(FORWARD_TIMEOUT_MILLIS) {
                HeartwoodSession.withClient(pairing) { client -> call(client, payload, otherPubkey.orEmpty()) }
            }
        } ?: return null

        return when (result) {
            is HeartwoodResult.Failure -> null
            is HeartwoodResult.Success -> buildResultCursor(result.value, includeEventAndSignature)
        }
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
        private const val COLUMN_RESULT = "result"
        private const val COLUMN_EVENT = "event"
        private const val COLUMN_SIGNATURE = "signature"
        private const val PONG = "pong"
        private const val FORWARD_TIMEOUT_MILLIS = 15_000L

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

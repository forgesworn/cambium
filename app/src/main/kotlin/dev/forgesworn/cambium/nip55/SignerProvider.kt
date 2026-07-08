package dev.forgesworn.cambium.nip55

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.forgesworn.cambium.pairing.PairingStore

/**
 * The NIP-55 "silent" path: clients query this content provider before falling back to the
 * visible [SignerActivity] intent. A `query()` here runs synchronously on the caller's binder
 * thread, so it must never do relay work.
 *
 * MVP behaviour, matching what Amber and Primal actually do (not the more permissive reading of
 * the NIP-55 text): `GET_PUBLIC_KEY` is declared in the manifest for discovery but always answers
 * `null` here -- both real implementations force login through the visible intent, never the
 * silent path. `PING` is the only authority this provider answers directly, and only for an
 * already-approved caller, so a client can cheaply check "is Cambium paired and will it talk to
 * me" without a relay round trip. Every other authority (SIGN_EVENT, NIP04_*, NIP44_*) returns
 * `null`, which per the NIP-55 contract tells the client to fall back to the intent.
 *
 * The caller is always taken from [getCallingPackage], never from query arguments -- a caller
 * cannot claim to be someone else by passing a different package name in.
 *
 * TODO (M4): Amethyst queries the provider first for every operation once an app is approved, not
 * just get_public_key. Upgrading SIGN_EVENT and the NIP04/NIP44 encrypt/decrypt authorities to
 * actually forward to Heartwood here is planned -- a 1-2s relay round trip is acceptable since the
 * client calls query() from a background thread. When that lands: the payload/other-pubkey/
 * current-user arguments arrive in
 * the `projection` array (not `selectionArgs` -- that's how Amber's real clients pass them,
 * despite the NIP-55 text describing selectionArgs), the result cursor needs a `result` column
 * (plus `event` and legacy `signature` for SIGN_EVENT), and a permanent per-app denial should
 * return a cursor with a `rejected` column ("true") rather than `null` -- `null` today just means
 * "not handled here, try the intent", which is not yet distinguished from "permanently blocked"
 * since Cambium has no persistent deny-list yet (see PairingStore).
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
    ): Cursor? {
        if (uri.authority != PING_AUTHORITY) {
            // GET_PUBLIC_KEY: always the intent, matching real-world signer behaviour (see class doc).
            // SIGN_EVENT, NIP04_*, NIP44_*: always the intent in this MVP (see TODO above).
            return null
        }

        val caller = callingPackage ?: return null
        if (!pairingStore.isApproved(caller)) return null
        if (!pairingStore.isPaired()) return null

        return MatrixCursor(arrayOf(COLUMN_RESULT)).apply {
            addRow(arrayOf(PONG))
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
        private const val PONG = "pong"
        const val GET_PUBLIC_KEY_AUTHORITY = "dev.forgesworn.cambium.GET_PUBLIC_KEY"
        const val PING_AUTHORITY = "dev.forgesworn.cambium.PING"
    }
}

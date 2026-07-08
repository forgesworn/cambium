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
 * thread, so it must never do relay work -- Cambium only answers `get_public_key`, and only from
 * the already-stored pairing for a caller that has already been approved. Every other method
 * returns `null`, which per the NIP-55 contract tells the client to fall back to the intent.
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
        if (uri.authority != GET_PUBLIC_KEY_AUTHORITY) {
            // SIGN_EVENT, NIP04_*, NIP44_*: always ask via the visible intent flow in this MVP.
            return null
        }

        val caller = callingPackage ?: return null
        if (!pairingStore.isApproved(caller)) return null

        val pairing = pairingStore.current() ?: return null

        return MatrixCursor(arrayOf(COLUMN_RESULT)).apply {
            addRow(arrayOf(pairing.signerPubkeyHex))
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
        const val GET_PUBLIC_KEY_AUTHORITY = "dev.forgesworn.cambium.GET_PUBLIC_KEY"
    }
}

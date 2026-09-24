package dev.forgesworn.cambium.unlock

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The boards this phone can unlock ([UnlockEnrolment]) and the keep-alive's ping record per paired
 * identity ([Reachability.Record]), in their own EncryptedSharedPreferences file, independent of
 * [dev.forgesworn.cambium.pairing.PairingStore]: unpairing an identity must not silently forget
 * that this phone can unlock the board, and "Unpair all" says so rather than doing it.
 *
 * The phone key K is stored here (encrypted at rest, readable without the owner present) because
 * the background listener needs it; the slot secret S is not, only its Keystore-sealed form.
 * Writes are `commit()`, not `apply()`: the service and the activities each construct their own
 * store, and a `last` prompt record that has not reached disk before the process dies would
 * re-prompt for the same restart.
 */
class UnlockStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun enrolments(): List<UnlockEnrolment> = synchronized(LOCK) {
        val raw = prefs.getString(KEY_ENROLMENTS, null) ?: return emptyList()
        runCatching { json.decodeFromString<List<UnlockEnrolment>>(raw) }.getOrDefault(emptyList())
    }

    fun enrolment(id: Long): UnlockEnrolment? = enrolments().firstOrNull { it.id == id }

    fun hasEnrolments(): Boolean = enrolments().isNotEmpty()

    /** Adds or replaces (by board record id). */
    fun put(enrolment: UnlockEnrolment) = update { list -> list.filterNot { it.id == enrolment.id } + enrolment }

    /** Applies [change] to one enrolment, if it still exists. */
    fun modify(id: Long, change: (UnlockEnrolment) -> UnlockEnrolment) =
        update { list -> list.map { if (it.id == id) change(it) else it } }

    /** Forgets a board and destroys its Keystore key. The board-side record is revoked separately. */
    fun remove(id: Long) {
        val removed = enrolment(id) ?: return
        update { list -> list.filterNot { it.id == id } }
        SlotSecretVault.delete(removed.keyAlias)
    }

    fun reachability(): Map<String, Reachability.Record> = synchronized(LOCK) {
        val raw = prefs.getString(KEY_REACHABILITY, null) ?: return emptyMap()
        runCatching { json.decodeFromString<Map<String, Reachability.Record>>(raw) }.getOrDefault(emptyMap())
    }

    fun setReachability(records: Map<String, Reachability.Record>) = synchronized(LOCK) {
        prefs.edit().putString(KEY_REACHABILITY, json.encodeToString(records)).commit()
    }

    private fun update(change: (List<UnlockEnrolment>) -> List<UnlockEnrolment>) = synchronized(LOCK) {
        prefs.edit().putString(KEY_ENROLMENTS, json.encodeToString(change(enrolments()))).commit()
    }

    private companion object {
        const val PREFS_NAME = "cambium_unlock"
        const val KEY_ENROLMENTS = "enrolments_json"
        const val KEY_REACHABILITY = "reachability_json"
        /** One lock for every instance: read-modify-write must not interleave across them. */
        val LOCK = Any()
        val json = Json { ignoreUnknownKeys = true }
    }
}

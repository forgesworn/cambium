package dev.forgesworn.cambium.log

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the activity log as a small JSON file in `filesDir`, not `EncryptedSharedPreferences`
 * -- this is metadata only (see [ActivityLogEntry]'s doc: never a payload, plaintext or
 * ciphertext), so there is nothing here that needs Keystore-backed encryption, and a flat file
 * suits an append-heavy, size-capped list better than shoehorning it into a key-value store. The
 * enabled toggle lives in its own small plain `SharedPreferences`, independent of `PairingStore`
 * -- this is a diagnostic/reassurance feature, not pairing state -- and defaults to **on**
 * (opt-out, not opt-in): the whole point is showing the user what Cambium has done on their
 * behalf, so it should work out of the box rather than needing to be found and switched on first.
 *
 * Reads and writes are small (capped at [ActivityLog.MAX_ENTRIES] entries) and synchronous,
 * called directly from whatever thread is already handling the request -- `SignerActivity`'s main
 * thread or `SignerProvider`'s binder thread -- rather than offloaded to a coroutine, the same way
 * `PairingStore`'s `EncryptedSharedPreferences` reads already are. `SignerActivity` calls this
 * immediately before `finish()`; offloading it to `lifecycleScope` would risk the write being
 * cancelled by the activity finishing before it completes.
 */
class ActivityLogStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** No-ops entirely -- does not even touch the file -- when logging is disabled. */
    @Synchronized
    fun append(entry: ActivityLogEntry) {
        if (!isEnabled()) return
        writeEntries(ActivityLog.append(readEntries(), entry))
    }

    /** Newest first, for display. */
    @Synchronized
    fun entries(): List<ActivityLogEntry> = readEntries().asReversed()

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun readEntries(): List<ActivityLogEntry> {
        if (!file.exists()) return emptyList()
        return runCatching { Json.decodeFromString<List<ActivityLogEntry>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun writeEntries(entries: List<ActivityLogEntry>) {
        file.writeText(Json.encodeToString(entries))
    }

    private companion object {
        const val FILE_NAME = "activity_log.json"
        const val PREFS_NAME = "cambium_activity_log"
        const val KEY_ENABLED = "enabled"
    }
}

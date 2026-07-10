package dev.forgesworn.cambium.log

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * `SignerActivity`, `SignerProvider` and `ActivityLogActivity` each construct their own
 * `ActivityLogStore` against the same underlying file -- an earlier version guarded reads/writes
 * with `@Synchronized`, which only ever serialises calls against *one* instance's own monitor, not
 * across the several instances actually writing to the same file concurrently. [mutex] and
 * [writerScope] live on the companion object instead, so every instance shares the exact same
 * serialisation regardless of which one constructed it.
 *
 * [append] is fire-and-forget: it enqueues the read-transform-write onto [writerScope] (a
 * dedicated single-thread dispatcher that outlives any one activity) and returns immediately,
 * never blocking the caller's thread on file I/O -- `SignerProvider`'s binder thread in
 * particular, where blocking would undercut a `DecryptCache` hit's whole point of answering
 * without a round trip (see `SignerProvider.forward`'s `HeartwoodOutcome.Cached` skip). Using a
 * scope that is not tied to any one activity's lifecycle, rather than `lifecycleScope`, also means
 * `SignerActivity` calling this immediately before `finish()` cannot have the write cancelled out
 * from under it by the activity finishing. [entries]/[clear] still block their caller briefly
 * (`runBlocking` under [mutex]) -- both are rare, user-initiated reads from `ActivityLogActivity`,
 * where "block until this specific answer/effect is visible" is the actually-wanted behaviour.
 */
class ActivityLogStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** No-ops entirely -- does not even touch the file or the writer queue -- when logging is
     * disabled. */
    fun append(entry: ActivityLogEntry) {
        if (!isEnabled()) return
        writerScope.launch {
            mutex.withLock {
                writeEntriesLocked(ActivityLog.append(readEntriesLocked(), entry))
            }
        }
    }

    /** Newest first, for display. */
    fun entries(): List<ActivityLogEntry> = runBlocking { mutex.withLock { readEntriesLocked() } }.asReversed()

    fun clear() {
        runBlocking { mutex.withLock { file.delete() } }
    }

    private fun readEntriesLocked(): List<ActivityLogEntry> {
        if (!file.exists()) return emptyList()
        return runCatching { Json.decodeFromString<List<ActivityLogEntry>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun writeEntriesLocked(entries: List<ActivityLogEntry>) {
        file.writeText(Json.encodeToString(entries))
    }

    private companion object {
        const val FILE_NAME = "activity_log.json"
        const val PREFS_NAME = "cambium_activity_log"
        const val KEY_ENABLED = "enabled"

        // Shared across every ActivityLogStore instance -- see the class doc. No parent job, like
        // HeartwoodSession's worker: an in-flight append must never be cancelled by whichever
        // activity happened to enqueue it finishing first.
        val mutex = Mutex()
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

package dev.forgesworn.cambium.log

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

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
 * A **process-wide singleton** ([getInstance]), not a plain constructor: `SignerActivity`,
 * `SignerProvider` and `ActivityLogActivity` each need to write to the same underlying file, and
 * an earlier version that let each construct its own instance guarded reads/writes with
 * `@Synchronized`, which only ever serialises calls against *one* instance's own monitor, not
 * across several instances racing on the same file. All file access here instead goes through
 * exactly one dedicated writer coroutine, on its own single-thread dispatcher, fed by an unbounded
 * [Channel] -- the same single-worker-plus-inbox shape `HeartwoodSession.Session` already uses for
 * the same reason: one consumer, one thread, nothing to race.
 *
 * [append] is fire-and-forget: [Channel.trySend] on an unbounded channel never suspends and never
 * fails, so it enqueues the entry and returns immediately without blocking the caller's thread on
 * file I/O -- `SignerProvider`'s binder thread in particular, where blocking would undercut a
 * `DecryptCache` hit's whole point of answering without a round trip (see `SignerProvider.forward`'s
 * `HeartwoodOutcome.Cached` skip, which additionally skips *enqueuing at all* for a silent-path
 * cache hit). The writer's dispatcher/scope are not tied to any one activity's lifecycle, so
 * `SignerActivity` calling this immediately before `finish()` cannot have the write cancelled out
 * from under it by the activity finishing. [entries]/[clear] still block their caller briefly (a
 * request-response round trip through the same channel, via `CompletableDeferred`) -- both are
 * rare, user-initiated calls from `ActivityLogActivity`, where blocking until the effect is
 * actually visible is the wanted behaviour, and routing them through the channel too (rather than
 * reading the file directly) keeps them correctly ordered relative to any in-flight appends.
 */
class ActivityLogStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private sealed interface Message {
        data class Append(val entry: ActivityLogEntry) : Message
        data class Read(val deferred: CompletableDeferred<List<ActivityLogEntry>>) : Message
        data class Clear(val deferred: CompletableDeferred<Unit>) : Message
    }

    private val inbox = Channel<Message>(capacity = Channel.UNLIMITED)

    init {
        val dispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "activity-log-writer").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        // No parent job: this outlives any one activity, same reasoning as HeartwoodSession's worker.
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            for (message in inbox) {
                when (message) {
                    is Message.Append -> writeEntries(ActivityLog.append(readEntries(), message.entry))
                    is Message.Read -> message.deferred.complete(readEntries())
                    is Message.Clear -> {
                        file.delete()
                        message.deferred.complete(Unit)
                    }
                }
            }
        }
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** No-ops entirely -- does not even touch the writer queue -- when logging is disabled. */
    fun append(entry: ActivityLogEntry) {
        if (!isEnabled()) return
        inbox.trySend(Message.Append(entry))
    }

    /** Newest first, for display. */
    fun entries(): List<ActivityLogEntry> {
        val deferred = CompletableDeferred<List<ActivityLogEntry>>()
        inbox.trySend(Message.Read(deferred))
        return runBlocking { deferred.await() }.asReversed()
    }

    fun clear() {
        val deferred = CompletableDeferred<Unit>()
        inbox.trySend(Message.Clear(deferred))
        runBlocking { deferred.await() }
    }

    private fun readEntries(): List<ActivityLogEntry> {
        if (!file.exists()) return emptyList()
        return runCatching { Json.decodeFromString<List<ActivityLogEntry>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun writeEntries(entries: List<ActivityLogEntry>) {
        file.writeText(Json.encodeToString(entries))
    }

    companion object {
        private const val FILE_NAME = "activity_log.json"
        private const val PREFS_NAME = "cambium_activity_log"
        private const val KEY_ENABLED = "enabled"

        @Volatile
        private var instance: ActivityLogStore? = null

        fun getInstance(context: Context): ActivityLogStore =
            instance ?: synchronized(this) {
                instance ?: ActivityLogStore(context.applicationContext).also { instance = it }
            }
    }
}

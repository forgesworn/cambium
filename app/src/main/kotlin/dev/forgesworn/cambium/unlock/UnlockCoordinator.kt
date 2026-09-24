package dev.forgesworn.cambium.unlock

import android.content.Context
import android.util.Log
import dev.forgesworn.cambium.signer.RelayWatch
import dev.forgesworn.cambium.signer.UnlockNostr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide owner of the lock listener: which relays it watches, the newest fresh unlock
 * request per board, and the deliveries this phone has sent. [dev.forgesworn.cambium.service.HeartwoodKeepAliveService]
 * keeps it running; [UnlockActivity] reads [requests] and calls [deliver].
 *
 * The decision logic is [LockMatcher] and [PhoneUnlock.judge], pure and JVM-tested; this object
 * only applies their verdicts. A prompt is shown once per restart (the board repeats its message
 * every minute under the same one-time key, and those repeats refresh [requests] silently). If a
 * repeat of the same restart arrives well after this phone answered it, the delivery did not take
 * (a revoked record, say), and the owner is told the board is still locked. Nothing here ever
 * pings the board: see [Reachability].
 */
object UnlockCoordinator {
    private const val TAG = "CambiumUnlock"
    /** Longer than a phone-unlock takes on the board, shorter than its 60 s repeat. */
    private const val STILL_LOCKED_AFTER_SECS = 25L

    data class Request(val match: LockMatch, val seenAtMillis: Long)

    private data class Sent(val authorHex: String, val boot: Long, val atMillis: Long)

    private val _requests = MutableStateFlow<Map<Long, Request>>(emptyMap())
    val requests: StateFlow<Map<Long, Request>> = _requests

    private val sent = ConcurrentHashMap<Long, Sent>()
    private val warnedStillLocked = ConcurrentHashMap.newKeySet<Long>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watch: RelayWatch? = null
    private var watchedRelays: List<String> = emptyList()

    /** Starts, restarts or stops the listener so it watches exactly the enrolled boards' relays. */
    suspend fun sync(context: Context) = mutex.withLock {
        val app = context.applicationContext
        val relays = LockMatcher.relayUnion(UnlockStore(app).enrolments())
        if (relays == watchedRelays && watch != null) return@withLock
        watch?.stop()
        watch = null
        watchedRelays = emptyList()
        if (relays.isEmpty()) return@withLock
        watch = RelayWatch.locks(relays) { raw -> onAnnouncement(app, raw) }
        watchedRelays = relays
        Log.i(TAG, "listening for lock messages on ${relays.size} relay(s)")
    }

    suspend fun stop() = mutex.withLock {
        watch?.stop()
        watch = null
        watchedRelays = emptyList()
    }

    /** The board's current request, if it asked within the last two announce periods. */
    fun currentRequest(enrolmentId: Long, nowMillis: Long = System.currentTimeMillis()): Request? =
        _requests.value[enrolmentId]?.takeIf { nowMillis - it.seenAtMillis <= PhoneUnlock.MAX_ANNOUNCE_AGE_SECS * 1000 }

    fun forget(enrolmentId: Long) {
        _requests.update { it - enrolmentId }
        sent.remove(enrolmentId)
        warnedStillLocked.remove(enrolmentId)
    }

    private fun onAnnouncement(app: Context, raw: RawAnnouncement) {
        val store = UnlockStore(app)
        val nowMillis = System.currentTimeMillis()
        val match = LockMatcher.match(raw, store.enrolments(), nowMillis / 1000) ?: return
        val id = match.enrolment.id

        // Any authentic message carries the board's relay list: follow it, whatever the verdict.
        val followed = LockMatcher.withRelaysFrom(match.enrolment, match.context)
        if (followed !== match.enrolment) {
            store.modify(id) { it.copy(relays = followed.relays) }
            scope.launch { sync(app) }
        }

        when (match.verdict) {
            Verdict.NOT_LOCKED, Verdict.STALE, Verdict.REPLAY -> {
                Log.i(TAG, "board $id: ${match.verdict.name.lowercase()} message, restart #${match.context.boot}")
            }
            Verdict.PROMPT -> {
                store.modify(id) { it.copy(last = LastPrompt(match.context.boot, raw.authorHex)) }
                sent.remove(id)
                warnedStillLocked.remove(id)
                _requests.update { it + (id to Request(match, nowMillis)) }
                UnlockNotifications.showRequest(app, match)
            }
            Verdict.DUPLICATE -> {
                _requests.update { it + (id to Request(match, nowMillis)) }
                val answered = sent[id]
                if (answered != null &&
                    answered.authorHex == raw.authorHex &&
                    raw.createdAt > answered.atMillis / 1000 + STILL_LOCKED_AFTER_SECS &&
                    nowMillis > answered.atMillis + STILL_LOCKED_AFTER_SECS * 1000 &&
                    warnedStillLocked.add(id)
                ) {
                    UnlockNotifications.showStillLocked(app, match)
                }
            }
        }
    }

    /**
     * Sends the unlock for [request] with the slot secret a biometric just released. The caller
     * wipes [slotSecret] afterwards. True once at least one relay accepted the delivery.
     */
    suspend fun deliver(context: Context, request: Request, slotSecret: ByteArray): Boolean {
        val app = context.applicationContext
        val match = request.match
        val event = UnlockNostr.deliveryEvent(
            match.announcement.authorHex,
            PhoneUnlock.deliveryJson(match.enrolment.id, slotSecret),
        )
        sync(app)
        val published = mutex.withLock { watch }?.publish(event) ?: false
        if (published) {
            sent[match.enrolment.id] = Sent(match.announcement.authorHex, match.context.boot, System.currentTimeMillis())
            warnedStillLocked.remove(match.enrolment.id)
            UnlockNotifications.cancelRequest(app, match.enrolment.id)
        }
        Log.i(TAG, "board ${match.enrolment.id}: delivery ${if (published) "published" else "not accepted by any relay"}")
        return published
    }
}

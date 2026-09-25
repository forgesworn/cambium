package dev.forgesworn.cambium.unlock

import android.content.Context
import android.util.Log
import dev.forgesworn.cambium.signer.RelayWatch
import dev.forgesworn.cambium.signer.UnlockNostr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 *
 * The enrolled boards are cached in memory ([boards]) and re-read on every [sync]: the listener
 * sees every lock message on its relays, almost all of them someone else's, and must not open
 * the encrypted store for each one.
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
    @Volatile private var boards: List<UnlockEnrolment> = emptyList()

    /**
     * Gates which relays are actually connected to: a board's relays at the point it is first seen
     * this process (pairing, enrolment, restart) are trusted immediately, but a relay a lock or
     * relay-update message teaches Cambium about while a board is already known waits out a jitter
     * first -- see [RelayGate] and [onAnnouncement].
     */
    private val relayGate = RelayGate()
    private val knownBoardIds = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Starts, restarts or stops the listener so it watches exactly the enrolled boards' relays.
     * Runs to completion even if the caller is cancelled (an activity closed mid-start): a relay
     * client started but never recorded in [watch] could never be stopped.
     */
    suspend fun sync(context: Context) = withContext(NonCancellable) {
        mutex.withLock {
            val app = context.applicationContext
            boards = UnlockStore(app).enrolments()
            // A board seen for the first time this process (fresh pairing, enrolment, or the
            // process's first sync after a restart) has its relays trusted at once: they came from
            // pairing/enrolment, not from a stray relay message. A board already known keeps
            // whatever relayGate already granted it -- a newly learned relay for it only becomes
            // ready once onAnnouncement's jitter elapses.
            val freshBoards = boards.filter { knownBoardIds.add(it.id) }
            if (freshBoards.isNotEmpty()) relayGate.trust(freshBoards.flatMap { it.relays })
            val relays = relayGate.ready(LockMatcher.relayUnion(boards))
            if (relays == watchedRelays && watch != null) return@withLock
            watch?.stop()
            watch = null
            watchedRelays = emptyList()
            if (relays.isEmpty()) return@withLock
            watch = RelayWatch.locks(relays) { raw -> onAnnouncement(app, raw) }
            watchedRelays = relays
            Log.i(TAG, "listening for lock messages on ${relays.size} relay(s)")
        }
    }

    suspend fun stop() = withContext(NonCancellable) {
        mutex.withLock {
            watch?.stop()
            watch = null
            watchedRelays = emptyList()
        }
    }

    /** The board's current request, if it asked within the last two announce periods. */
    fun currentRequest(enrolmentId: Long, nowMillis: Long = System.currentTimeMillis()): Request? =
        _requests.value[enrolmentId]?.takeIf { nowMillis - it.seenAtMillis <= PhoneUnlock.MAX_ANNOUNCE_AGE_SECS * 1000 }

    fun forget(enrolmentId: Long) {
        _requests.update { it - enrolmentId }
        sent.remove(enrolmentId)
        warnedStillLocked.remove(enrolmentId)
        knownBoardIds.remove(enrolmentId)
        // The caller removes the enrolment from the store before calling this, but boards (this
        // process's cache) has not been reloaded yet, so the forgotten board's relays are still
        // here to prune -- unless another remaining board still needs one of them.
        val forgotten = boards.firstOrNull { it.id == enrolmentId }
        if (forgotten != null) {
            val stillNeeded = boards.asSequence().filter { it.id != enrolmentId }.flatMap { it.relays }.toSet()
            relayGate.untrust(forgotten.relays.filterNot { it in stillNeeded })
        }
    }

    private fun onAnnouncement(app: Context, raw: RawAnnouncement) {
        val nowMillis = System.currentTimeMillis()
        val match = LockMatcher.match(raw, boards, nowMillis / 1000) ?: return
        val id = match.enrolment.id
        val store = UnlockStore(app)

        // Any authentic message carries the board's relay list: follow it, whatever the verdict.
        // The relay list itself is trusted and persisted at once; connecting to any brand-new
        // relay in it is delayed by a jitter (relayGate.learn), so a relay Cambium has never spoken
        // to before cannot correlate its first connection to the moment this message arrived.
        val followed = LockMatcher.withRelaysFrom(match.enrolment, match.context)
        if (followed !== match.enrolment) {
            val newRelays = followed.relays.filterNot { it in match.enrolment.relays }
            store.modify(id) { it.copy(relays = followed.relays) }
            boards = boards.map { if (it.id == id) it.copy(relays = followed.relays) else it }
            relayGate.learn(scope, newRelays) { scope.launch { sync(app) } }
        }

        when (match.verdict) {
            Verdict.NOT_LOCKED, Verdict.STALE, Verdict.REPLAY -> {
                Log.i(TAG, "board $id: ${match.verdict.name.lowercase()} message, restart #${match.context.boot}")
            }
            Verdict.PROMPT -> {
                val last = LastPrompt(match.context.boot, raw.authorHex)
                store.modify(id) { it.copy(last = last) }
                boards = boards.map { if (it.id == id) it.copy(last = last) else it }
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
     * wipes [slotSecret] afterwards and has already checked [request] is still the board's
     * current one. The delivery goes only to the relays the board itself listed, not to every
     * relay this phone listens on. True once at least one relay accepted it.
     *
     * The board may have moved to a brand-new relay less than a jitter ago (that is exactly what
     * the relay-update message is for): if we waited out [relayGate]'s jitter here too, a delivery
     * could sit unconnected to the board's new relay for up to 10 minutes. The owner's tap already
     * exposes the timing -- there is nothing left to protect by delaying it -- so a genuine lock
     * prompt's own relays are trusted immediately, before [sync] rebuilds the watch and waits for
     * the connection. [match]'s verdict is only ever `PROMPT` for `t == "locked"` (see
     * [PhoneUnlock.judge]), so a relay-update can never reach this method, but the check is kept
     * explicit rather than relied on implicitly.
     */
    suspend fun deliver(context: Context, request: Request, slotSecret: ByteArray): Boolean {
        val app = context.applicationContext
        val match = request.match
        val event = UnlockNostr.deliveryEvent(
            match.announcement.authorHex,
            PhoneUnlock.deliveryJson(match.enrolment.id, slotSecret),
        )
        if (match.context.t == PhoneUnlock.TYPE_LOCKED) {
            relayGate.trust(match.context.relays.map { it.trimEnd('/') }.filter(::isRelayUrl))
        }
        sync(app)
        val targets = match.context.relays.map { it.trimEnd('/') }.filter(::isRelayUrl)
            .ifEmpty { match.enrolment.relays }
        val published = mutex.withLock { watch }?.publish(event, targets) ?: false
        if (published) {
            sent[match.enrolment.id] = Sent(match.announcement.authorHex, match.context.boot, System.currentTimeMillis())
            warnedStillLocked.remove(match.enrolment.id)
            // Only if no newer restart has asked in the meantime: that prompt must stay up.
            if (currentRequest(match.enrolment.id)?.match?.announcement?.authorHex == match.announcement.authorHex) {
                UnlockNotifications.cancelRequest(app, match.enrolment.id)
            }
        }
        Log.i(TAG, "board ${match.enrolment.id}: delivery ${if (published) "published" else "not accepted by any relay"}")
        return published
    }
}

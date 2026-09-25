package dev.forgesworn.cambium.unlock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.time.Duration
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * How long Cambium waits before connecting to a relay a message just taught it about, so that
 * relay -- one it has never spoken to before -- cannot tie this phone's first connection to the
 * exact moment the board's broadcast reached it. A relay Cambium already knows (from pairing,
 * enrolment, or a previous session) is connected immediately; see [RelayGate].
 */
object RelayJitter {
    val MIN: Duration = Duration.ofSeconds(30)
    val MAX: Duration = Duration.ofMinutes(10)

    /** Uniform in [[min], [max]), drawn from [random] -- a [SecureRandom] by default. */
    fun next(random: Random = SecureRandom(), min: Duration = MIN, max: Duration = MAX): Duration {
        require(!max.isNegative && max >= min) { "max must be at least min" }
        val spanMillis = max.toMillis() - min.toMillis()
        val offsetMillis = if (spanMillis <= 0L) 0L else (random.nextDouble() * spanMillis).toLong()
        return min.plusMillis(offsetMillis)
    }
}

/**
 * Which relays [UnlockCoordinator] may connect to right now. A relay already known -- from
 * pairing, enrolment, or a previous session -- is ready immediately via [trust]; a relay a message
 * just taught Cambium about is withheld until a random jitter elapses ([learn]), so it cannot
 * correlate its first connection from this phone to the moment the board's broadcast changed.
 *
 * Pure Kotlin bar the coroutine delay itself, so tests can run [learn] against virtual time
 * (`kotlinx.coroutines.test`) instead of real minutes, and supply their own [jitter].
 */
class RelayGate(private val jitter: () -> Duration = { RelayJitter.next() }) {
    private val readyRelays = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** The subset of [relays] safe to connect to right now. */
    fun ready(relays: Collection<String>): List<String> = relays.filter { it in readyRelays }

    /** Marks [relays] ready with no delay: already known, not something a message just taught us. */
    fun trust(relays: Collection<String>) {
        readyRelays += relays
    }

    /**
     * Drops [relays] from the ready set -- a board being forgotten. Cheap best-effort cleanup, not
     * required for correctness: a relay already scheduled in [learn] (`pending`) is left alone and
     * simply becomes ready, harmlessly, whenever its jitter elapses.
     */
    fun untrust(relays: Collection<String>) {
        readyRelays -= relays.toSet()
    }

    /**
     * Schedules each of [relays] not already ready or already scheduled to become ready after a
     * jitter, on [scope], calling [onReady] once each one does. Returns immediately -- the wait
     * happens in the launched coroutines, never on the caller, so this never delays the caller's
     * own work (in particular, an unlock delivery must stay fast; see [UnlockCoordinator.deliver]).
     */
    fun learn(scope: CoroutineScope, relays: Collection<String>, onReady: () -> Unit) {
        for (relay in relays) {
            if (relay in readyRelays || !pending.add(relay)) continue
            val wait = jitter()
            scope.launch {
                delay(wait.toMillis())
                readyRelays += relay
                pending -= relay
                onReady()
            }
        }
    }
}

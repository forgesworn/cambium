package dev.forgesworn.cambium.unlock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RelayGate] against virtual time (`kotlinx.coroutines.test`), so a 30 s-10 min jitter runs in
 * milliseconds here rather than really waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayGateTest {

    @Test
    fun `a trusted relay is ready immediately, with no jitter call at all`() {
        var jitterCalls = 0
        val gate = RelayGate(jitter = { jitterCalls++; Duration.ofMinutes(5) })
        gate.trust(listOf("wss://known.example"))
        assertEquals(listOf("wss://known.example"), gate.ready(listOf("wss://known.example", "wss://unknown.example")))
        assertEquals(0, jitterCalls)
    }

    @Test
    fun `a learned relay is withheld until its jitter elapses`() = runTest {
        val gate = RelayGate(jitter = { Duration.ofMinutes(2) })
        var readyCalls = 0
        gate.learn(scope = this, relays = listOf("wss://new.example")) { readyCalls++ }
        // Not ready yet: neither the relay nor the onReady callback have fired.
        assertEquals(emptyList(), gate.ready(listOf("wss://new.example")))
        assertEquals(0, readyCalls)

        advanceTimeBy(Duration.ofMinutes(2).toMillis() - 1)
        assertEquals(emptyList(), gate.ready(listOf("wss://new.example")))
        assertEquals(0, readyCalls)

        advanceUntilIdle()
        assertEquals(listOf("wss://new.example"), gate.ready(listOf("wss://new.example")))
        assertEquals(1, readyCalls)
    }

    @Test
    fun `learning the same relay twice schedules only one jitter`() = runTest {
        var jitterCalls = 0
        val gate = RelayGate(jitter = { jitterCalls++; Duration.ofSeconds(30) })
        var readyCalls = 0
        gate.learn(this, listOf("wss://new.example")) { readyCalls++ }
        gate.learn(this, listOf("wss://new.example")) { readyCalls++ }
        advanceUntilIdle()
        assertEquals(1, jitterCalls, "already-pending relay must not schedule a second jitter")
        assertEquals(1, readyCalls)
        assertEquals(listOf("wss://new.example"), gate.ready(listOf("wss://new.example")))
    }

    @Test
    fun `an already-trusted relay is not re-jittered when learned again`() = runTest {
        var jitterCalls = 0
        val gate = RelayGate(jitter = { jitterCalls++; Duration.ofMinutes(1) })
        gate.trust(listOf("wss://known.example"))
        var readyCalls = 0
        gate.learn(this, listOf("wss://known.example")) { readyCalls++ }
        advanceUntilIdle()
        assertEquals(0, jitterCalls)
        assertEquals(0, readyCalls, "trust() already made it ready; learn() has nothing new to report")
        assertEquals(listOf("wss://known.example"), gate.ready(listOf("wss://known.example")))
    }

    @Test
    fun `learn returns immediately -- the wait happens in the launched coroutine, not the caller`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val gate = RelayGate(jitter = { Duration.ofMinutes(10) })
        var readyCalls = 0
        // learn() itself must not suspend: this call returns without the dispatcher ever running.
        gate.learn(scope, listOf("wss://new.example")) { readyCalls++ }
        assertTrue(dispatcher.scheduler.currentTime == 0L)
        assertEquals(0, readyCalls)
    }

    @Test
    fun `each learned relay draws its own jitter`() = runTest {
        var draws = 0
        val gate = RelayGate(jitter = { draws++; Duration.ofSeconds(1) })
        gate.learn(this, listOf("wss://a.example", "wss://b.example")) {}
        assertEquals(2, draws)
    }

    /**
     * The bug UnlockCoordinator.deliver guards against: a board moved to a brand-new relay less
     * than a jitter ago (a relay-update) and then restarted locked, announcing on that same new
     * relay. If a genuine lock delivery had to wait out the jitter too, it would go to the board's
     * stale relay for up to 10 minutes -- exactly the case the relay-update exists to avoid.
     * `trust()` is how `deliver` escapes the jitter: it must make an already-learn()-scheduled
     * relay ready at once, with the pending jitter's own callback still safe to fire later.
     */
    @Test
    fun `a lock delivery trusting an already-jittered relay makes it ready immediately`() = runTest {
        var jitterCalls = 0
        val gate = RelayGate(jitter = { jitterCalls++; Duration.ofMinutes(9) })
        var readyCalls = 0
        // A passive relay-update taught Cambium about this relay a moment ago; still on jitter.
        gate.learn(this, listOf("wss://new.example")) { readyCalls++ }
        assertEquals(emptyList(), gate.ready(listOf("wss://new.example")))

        // The board then restarts locked and announces on that same relay; the owner taps unlock.
        gate.trust(listOf("wss://new.example"))
        assertEquals(listOf("wss://new.example"), gate.ready(listOf("wss://new.example")), "trust() must not wait for the jitter")

        // The original jitter is still scheduled; letting it elapse must not misbehave (it simply
        // re-confirms readiness and fires its own onReady once, harmlessly).
        advanceUntilIdle()
        assertEquals(1, jitterCalls)
        assertEquals(1, readyCalls)
        assertEquals(listOf("wss://new.example"), gate.ready(listOf("wss://new.example")))
    }

    @Test
    fun `untrust drops a relay from the ready set`() {
        val gate = RelayGate(jitter = { Duration.ofMinutes(1) })
        gate.trust(listOf("wss://a.example", "wss://b.example"))
        gate.untrust(listOf("wss://a.example"))
        assertEquals(listOf("wss://b.example"), gate.ready(listOf("wss://a.example", "wss://b.example")))
    }
}

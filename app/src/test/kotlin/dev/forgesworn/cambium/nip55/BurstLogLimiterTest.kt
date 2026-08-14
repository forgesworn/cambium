package dev.forgesworn.cambium.nip55

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BurstLogLimiterTest {

    @Test
    fun `the first occurrence reports immediately and repeats are aggregated`() {
        val now = AtomicLong(1_000L)
        val limiter = BurstLogLimiter(intervalMillis = 5_000L, elapsedRealtimeMillis = now::get)

        assertEquals(1, limiter.record("amethyst")?.occurrences)
        repeat(8) { assertNull(limiter.record("amethyst")) }

        now.addAndGet(5_001L)
        assertEquals(9, limiter.record("amethyst")?.occurrences)
        assertNull(limiter.record("amethyst"))
    }

    @Test
    fun `aggregation is partitioned per caller`() {
        val now = AtomicLong(1_000L)
        val limiter = BurstLogLimiter(intervalMillis = 5_000L, elapsedRealtimeMillis = now::get)

        assertEquals(1, limiter.record("amethyst")?.occurrences)
        assertNull(limiter.record("amethyst"))
        assertEquals(1, limiter.record("regress")?.occurrences)
    }
}

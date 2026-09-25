package dev.forgesworn.cambium.unlock

import java.time.Duration
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RelayJitterTest {

    private fun fixed(value: Double) = object : Random() {
        override fun nextDouble(): Double = value
    }

    @Test
    fun `defaults span 30 seconds to 10 minutes`() {
        assertEquals(Duration.ofSeconds(30), RelayJitter.MIN)
        assertEquals(Duration.ofMinutes(10), RelayJitter.MAX)
    }

    @Test
    fun `the lowest draw returns the minimum, a high draw approaches the maximum`() {
        assertEquals(Duration.ofSeconds(30), RelayJitter.next(fixed(0.0)))
        val span = RelayJitter.MAX.toMillis() - RelayJitter.MIN.toMillis()
        assertEquals(RelayJitter.MIN.plusMillis((span * 0.5).toLong()), RelayJitter.next(fixed(0.5)))
        val nearMax = RelayJitter.next(fixed(0.999999))
        assertTrue(nearMax < RelayJitter.MAX, "expected $nearMax below the maximum")
        assertTrue(nearMax > RelayJitter.MIN, "expected $nearMax above the minimum")
    }

    @Test
    fun `a real SecureRandom source always lands inside the bounds`() {
        repeat(200) {
            val wait = RelayJitter.next()
            assertTrue(wait >= RelayJitter.MIN, "wait $wait below minimum")
            assertTrue(wait < RelayJitter.MAX || wait == RelayJitter.MAX, "wait $wait above maximum")
        }
    }

    @Test
    fun `custom bounds are honoured`() {
        val min = Duration.ofSeconds(1)
        val max = Duration.ofSeconds(2)
        assertEquals(min, RelayJitter.next(fixed(0.0), min, max))
        assertEquals(max, RelayJitter.next(fixed(1.0), min, max))
    }

    @Test
    fun `a maximum below the minimum is refused`() {
        assertFailsWith<IllegalArgumentException> {
            RelayJitter.next(fixed(0.0), min = Duration.ofMinutes(1), max = Duration.ofSeconds(1))
        }
    }
}

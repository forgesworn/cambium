package dev.forgesworn.cambium.signer

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignCacheTest {
    @Test
    fun `an exact event is cached only until its ttl`() {
        val now = AtomicLong(1_000L)
        val cache = SignCache(ttlMillis = 5_000L, elapsedRealtimeMillis = now::get)
        val request = CacheableSign("unsigned auth")

        cache.put(request, "signed auth")
        assertEquals("signed auth", cache.get(request))
        now.addAndGet(5_001L)
        assertNull(cache.get(request))
    }

    @Test
    fun `the cache is bounded and keys exact event contents`() {
        val cache = SignCache(maxEntries = 2)
        val first = CacheableSign("first")
        val second = CacheableSign("second")
        val third = CacheableSign("third")

        cache.put(first, "signed first")
        cache.put(second, "signed second")
        cache.put(third, "signed third")

        assertNull(cache.get(first))
        assertEquals("signed second", cache.get(second))
        assertEquals("signed third", cache.get(third))
        assertEquals(2, cache.size())
    }
}

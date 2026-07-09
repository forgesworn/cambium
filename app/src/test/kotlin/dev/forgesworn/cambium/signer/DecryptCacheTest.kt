package dev.forgesworn.cambium.signer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DecryptCacheTest {

    private fun request(payload: String = "cafebabe", pubkey: String = "a".repeat(64)) =
        CacheableDecrypt(CacheableDecrypt.Method.NIP44, pubkey, payload)

    @Test
    fun `returns null on a miss`() {
        val cache = DecryptCache()
        assertNull(cache.get(request()))
    }

    @Test
    fun `returns a cached success on a hit`() {
        val cache = DecryptCache()
        val key = request()
        cache.putSuccess(key, "decrypted plaintext")

        val hit = assertIs<CachedOutcome.Success>(cache.get(key))
        assertEquals("decrypted plaintext", hit.value)
    }

    @Test
    fun `caches a deterministic decryption failure`() {
        val cache = DecryptCache()
        val key = request()
        cache.putDeterministicFailure(key, "decryption failed")

        val cached = assertIs<CachedOutcome.DeterministicFailure>(cache.get(key))
        assertEquals("decryption failed", cached.message)
    }

    @Test
    fun `different payloads or counterparties are different cache keys`() {
        val cache = DecryptCache()
        cache.putSuccess(request(payload = "one"), "plain-one")

        assertNull(cache.get(request(payload = "two")))
        assertNull(cache.get(request(pubkey = "b".repeat(64))))
    }

    @Test
    fun `zap and nip04 are separate cache namespaces even for identical payload and pubkey`() {
        val cache = DecryptCache()
        val pubkey = "a".repeat(64)
        cache.putSuccess(CacheableDecrypt(CacheableDecrypt.Method.NIP04, pubkey, "same"), "nip04-plaintext")

        assertNull(cache.get(CacheableDecrypt(CacheableDecrypt.Method.ZAP, pubkey, "same")))
    }

    @Test
    fun `evicts the least recently used entry once over capacity`() {
        val cache = DecryptCache(maxEntries = 2)
        cache.putSuccess(request(payload = "one"), "plain-one")
        cache.putSuccess(request(payload = "two"), "plain-two")
        cache.putSuccess(request(payload = "three"), "plain-three")

        assertNull(cache.get(request(payload = "one")))
        assertEquals(2, cache.size())
    }

    @Test
    fun `touching an entry protects it from being the next eviction`() {
        val cache = DecryptCache(maxEntries = 2)
        cache.putSuccess(request(payload = "one"), "plain-one")
        cache.putSuccess(request(payload = "two"), "plain-two")
        cache.get(request(payload = "one")) // touch -- "two" is now the least recently used
        cache.putSuccess(request(payload = "three"), "plain-three")

        assertNull(cache.get(request(payload = "two")))
        assertIs<CachedOutcome.Success>(cache.get(request(payload = "one")))
    }

    @Test
    fun `clear removes every entry`() {
        val cache = DecryptCache()
        cache.putSuccess(request(payload = "one"), "plain-one")

        cache.clear()

        assertNull(cache.get(request(payload = "one")))
        assertEquals(0, cache.size())
    }

    @Test
    fun `deterministic decrypt failures are recognised from the firmware wording`() {
        assertEquals(true, isDeterministicDecryptFailure(HeartwoodError.Protocol("Decryption failed: bad ciphertext")))
        assertEquals(true, isDeterministicDecryptFailure(HeartwoodError.Protocol("DECRYPTION FAILED")))
    }

    @Test
    fun `transient or repairable failures are not treated as deterministic`() {
        assertEquals(false, isDeterministicDecryptFailure(HeartwoodError.Timeout))
        assertEquals(false, isDeterministicDecryptFailure(HeartwoodError.NotConnected))
        assertEquals(false, isDeterministicDecryptFailure(HeartwoodError.Protocol("unauthorised")))
        assertEquals(false, isDeterministicDecryptFailure(HeartwoodError.InvalidInput("bad hex")))
    }
}

package dev.forgesworn.cambium.signer

import dev.forgesworn.cambium.toHex
import java.security.MessageDigest

/** An exact unsigned event which may safely reuse the same signed result for a short period. */
data class CacheableSign(val unsignedEventJson: String)

/**
 * Small, bounded, per-identity cache for exact NIP-42 AUTH signatures. Amethyst can submit the
 * same relay challenge concurrently through several connections; signing those byte-identical
 * events more than once adds Heartwood round trips but no security or protocol value. Callers
 * decide which events are eligible -- Cambium only opts kind 22242 into this cache, never notes,
 * reactions, encryption, or arbitrary signing requests.
 */
class SignCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry(val signedEventJson: String, val storedAtMillis: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(request: CacheableSign): String? {
        val key = keyFor(request)
        val entry = entries[key] ?: return null
        if (elapsedRealtimeMillis() - entry.storedAtMillis > ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.signedEventJson
    }

    @Synchronized
    fun put(request: CacheableSign, signedEventJson: String) {
        entries[keyFor(request)] = Entry(signedEventJson, elapsedRealtimeMillis())
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun keyFor(request: CacheableSign): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(request.unsignedEventJson.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 128
        const val DEFAULT_TTL_MILLIS = 60_000L
    }
}

package dev.forgesworn.cambium.signer

import java.security.MessageDigest

/**
 * Identifies a decrypt call for caching purposes. Only the two decrypt methods are cacheable:
 * decryption is deterministic (the same ciphertext and counterparty always resolve the same way),
 * unlike encryption (nonce freshness means the same plaintext must never reuse a cached
 * ciphertext) or signing (must always go to Heartwood).
 */
data class CacheableDecrypt(
    val method: Method,
    val otherPubkeyHex: String,
    val payload: String,
) {
    enum class Method { NIP04, NIP44 }
}

sealed interface CachedOutcome {
    data class Success(val value: String) : CachedOutcome
    /** A deterministic failure (e.g. the firmware's "decryption failed") -- safe to answer forever. */
    data class DeterministicFailure(val message: String) : CachedOutcome
}

/**
 * A small in-memory LRU for [CacheableDecrypt] outcomes, consulted by [HeartwoodSession] on both
 * the silent and intent paths before admission control. Amethyst (and presumably other clients)
 * re-request the same decrypt repeatedly while browsing -- including content that will never
 * decrypt (legacy "Could not decrypt" items) -- and each retry otherwise costs a full 1-2s
 * hardware round trip. Pure Kotlin: no Android, so it stays JVM-testable the same way the
 * pairing parsers do.
 */
class DecryptCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private val entries = object : LinkedHashMap<String, CachedOutcome>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedOutcome>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(request: CacheableDecrypt): CachedOutcome? = entries[keyFor(request)]

    @Synchronized
    fun putSuccess(request: CacheableDecrypt, value: String) {
        entries[keyFor(request)] = CachedOutcome.Success(value)
    }

    @Synchronized
    fun putDeterministicFailure(request: CacheableDecrypt, message: String) {
        entries[keyFor(request)] = CachedOutcome.DeterministicFailure(message)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    /** Bounds the key size regardless of payload length: (method, counterparty, sha-256 of payload). */
    private fun keyFor(request: CacheableDecrypt): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(request.payload.toByteArray(Charsets.UTF_8))
        val payloadHash = digest.joinToString("") { byte -> "%02x".format(byte) }
        return "${request.method}:${request.otherPubkeyHex}:$payloadHash"
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 512
    }
}

/** Whether [error] deterministically means "this cannot be decrypted", as opposed to a
 * transient/repairable failure (timeout, not connected, an auth/policy refusal). Only this kind
 * gets cached -- see [DecryptCache]. */
fun isDeterministicDecryptFailure(error: HeartwoodError): Boolean {
    val message = (error as? HeartwoodError.Protocol)?.message ?: return false
    return message.contains("decryption failed", ignoreCase = true)
}

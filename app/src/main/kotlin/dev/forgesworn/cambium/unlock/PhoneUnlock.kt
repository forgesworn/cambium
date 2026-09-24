package dev.forgesworn.cambium.unlock

import dev.forgesworn.cambium.toHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The phone half of Heartwood's phone unlock, wire format v1. Pure Kotlin (javax.crypto's
 * HmacSHA256 and [ChaCha20]), JVM-tested against the firmware's own vectors
 * (`heartwood-esp32` `common/tests/fixtures/phone-unlock-v1.json`, copied into this module's test
 * resources). The firmware (`common/src/phone_unlock.rs`) and the Node bench client
 * (`scripts/lib/phone-unlock.mjs`) are the two other implementations held to them.
 *
 * ```
 * K       = HKDF-SHA256(salt SALT, ikm S, info "phone")                       32 bytes
 * hint    = hex(HMAC-SHA256(K, "hint" || author))[..16]
 * okm     = HKDF-SHA256(salt SALT, ikm K, info "announce" || author)          64 bytes
 * content = base64(0x01 || nonce(12) || ChaCha20(okm[0..32], nonce) ^ json
 *                  || HMAC-SHA256(okm[32..64], 0x01 || nonce || ct))
 * ```
 *
 * S is the slot secret the board handed this phone at enrolment. It unlocks the board, so it lives
 * behind a biometric-bound Keystore key (see [SlotSecretVault]) and is decrypted only for a
 * delivery. The byte arrays holding it are wiped, but the delivery JSON and the NIP-44 call need it
 * as a String, which the JVM cannot wipe; those copies live until the garbage collector reclaims
 * them. K is derived from it and only recognises and reads lock messages; it is kept where the
 * background listener can reach it.
 */
object PhoneUnlock {
    const val ANNOUNCE_KIND = 24135
    const val DELIVERY_KIND = 24136
    /**
     * The enrolment hand-off, Sapwood (or the bench script) to phone: an ephemeral event from a
     * throwaway key, tagged `["h", R]` with the phone's one-off rendezvous tag. Not in the
     * firmware, which never sees it.
     */
    const val HANDOFF_KIND = 24137
    const val HINT_TAG = "h"
    const val MAX_CONTENT_LEN = 2048
    /** Two announce periods: a phone that slept through one still catches the next. */
    const val MAX_ANNOUNCE_AGE_SECS = 120L
    const val MAX_FUTURE_SKEW_SECS = 60L

    const val TYPE_LOCKED = "locked"
    const val TYPE_RELAYS = "relays"

    private val SALT = "heartwood-phone-unlock-v1".toByteArray()
    private const val SEALED_VERSION: Byte = 1
    private const val NONCE_LEN = 12
    private const val TAG_LEN = 32

    private val json = Json { ignoreUnknownKeys = true }

    /** K from the slot secret S. */
    fun phoneKey(slotSecret: ByteArray): ByteArray {
        require(slotSecret.size == 32) { "slot secret must be 32 bytes" }
        return hkdf(slotSecret, "phone".toByteArray(), 32)
    }

    /** The `h` tag value a board posts for [author] (its per-boot one-time pubkey) under [phoneKey]. */
    fun hint(phoneKey: ByteArray, author: ByteArray): String =
        hmac(phoneKey, "hint".toByteArray() + author).toHex().substring(0, 16)

    fun hintMatches(phoneKey: ByteArray, author: ByteArray, received: String): Boolean =
        MessageDigest.isEqual(hint(phoneKey, author).toByteArray(), received.toByteArray())

    /**
     * Opens a sealed lock message, or returns null if it was not sealed to this [phoneKey] and
     * [author], was tampered with, or is not a v1 context. Never throws on hostile input: every
     * 24135 on the relay reaches this function once its hint matches.
     */
    fun openContext(phoneKey: ByteArray, author: ByteArray, content: String): LockContext? {
        if (content.length > MAX_CONTENT_LEN) return null
        val blob = runCatching { Base64.getDecoder().decode(content) }.getOrNull() ?: return null
        // Canonical encoding only, as the firmware and the Node client require.
        if (Base64.getEncoder().encodeToString(blob) != content) return null
        if (blob.size < 1 + NONCE_LEN + TAG_LEN || blob[0] != SEALED_VERSION) return null
        val body = blob.copyOfRange(0, blob.size - TAG_LEN)
        val tag = blob.copyOfRange(blob.size - TAG_LEN, blob.size)
        val (enc, mac) = contentKeys(phoneKey, author)
        try {
            if (!MessageDigest.isEqual(hmac(mac, body), tag)) return null
            val plain = ChaCha20.xor(enc, body.copyOfRange(1, 1 + NONCE_LEN), body.copyOfRange(1 + NONCE_LEN, body.size))
            val context = runCatching { json.decodeFromString<LockContext>(String(plain, Charsets.UTF_8)) }.getOrNull()
            return context?.takeIf { it.v == 1 }
        } finally {
            enc.fill(0)
            mac.fill(0)
        }
    }

    /** The firmware's side, kept for the vector and round-trip tests. */
    internal fun sealContext(phoneKey: ByteArray, author: ByteArray, contextJson: String, nonce: ByteArray): String {
        val (enc, mac) = contentKeys(phoneKey, author)
        val body = byteArrayOf(SEALED_VERSION) + nonce + ChaCha20.xor(enc, nonce, contextJson.toByteArray())
        return Base64.getEncoder().encodeToString(body + hmac(mac, body))
    }

    /** The delivery plaintext the board expects inside NIP-44: `{"v":1,"id":<id>,"s":"<hex S>"}`. */
    fun deliveryJson(id: Long, slotSecret: ByteArray): String =
        "{\"v\":1,\"id\":$id,\"s\":\"${slotSecret.toHex()}\"}"

    /**
     * Whether to prompt for a lock message. [last] is the newest announcement this phone already
     * prompted for on this board. A repeat needs the same count AND author: the board repeats its
     * announcement all boot under one author and every boot has a new one, so a board whose count
     * failed to persist (same count, new author) still prompts, and a lower count is a replay.
     */
    fun judge(context: LockContext, authorHex: String, createdAt: Long, now: Long, last: LastPrompt?): Verdict {
        if (context.t != TYPE_LOCKED) return Verdict.NOT_LOCKED
        if (createdAt + MAX_ANNOUNCE_AGE_SECS < now || createdAt > now + MAX_FUTURE_SKEW_SECS) return Verdict.STALE
        if (last != null && context.boot < last.boot) return Verdict.REPLAY
        if (last != null && context.boot == last.boot && last.authorHex == authorHex) return Verdict.DUPLICATE
        return Verdict.PROMPT
    }

    private fun contentKeys(phoneKey: ByteArray, author: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = hkdf(phoneKey, "announce".toByteArray() + author, 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64).also { okm.fill(0) }
    }

    /** RFC 5869 with SHA-256 and [SALT]. */
    private fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(SALT, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - written)
            previous.copyInto(out, written, 0, n)
            written += n
            counter++
        }
        prk.fill(0)
        previous.fill(0)
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

/** What a lock message says. All of it is labelling, not proof: whoever holds the board's flash
 * can write any of it. Field order matches the firmware's `LockContext`. */
@Serializable
data class LockContext(
    val v: Int,
    val t: String,
    val id: Long,
    val boot: Long,
    val reset: String,
    val ssid: String,
    val bssid: String,
    val fw: String,
    val relays: List<String>,
)

@Serializable
data class LastPrompt(val boot: Long, val authorHex: String)

enum class Verdict { PROMPT, DUPLICATE, STALE, REPLAY, NOT_LOCKED }

/** Lowercase or uppercase hex to bytes, or null for anything else (odd length, stray characters). */
internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

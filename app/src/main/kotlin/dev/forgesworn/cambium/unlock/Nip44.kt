package dev.forgesworn.cambium.unlock

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-44 v2, pure Kotlin ([Secp256k1] for the ECDH, [ChaCha20] for the stream cipher already used
 * for phone unlock's own sealed messages). Only used to build and check the invite reply's content
 * (`InviteReply.kt`): rust-nostr's `nip44Encrypt` (see `signer/UnlockRelay.kt`) exposes no way to
 * pin the nonce, and cannot run on the host JVM at all (native code per ABI), so it cannot be held
 * to the shared Sapwood/Cambium test vector directly. [encrypt] takes the nonce explicitly so a
 * test can fix it; production code draws 32 random bytes.
 */
internal object Nip44 {
    private const val VERSION: Byte = 2
    private const val NONCE_LEN = 32
    private const val MAC_LEN = 32
    private val SALT = "nip44-v2".toByteArray(Charsets.UTF_8)

    /** `secp256k1_ecdh(secretKey, pubkeyXOnlyHex)` run through `hkdf_extract(salt="nip44-v2", ikm)`. */
    private fun conversationKey(secretKey: ByteArray, pubkeyXOnlyHex: String): ByteArray? {
        val pubkey = pubkeyXOnlyHex.hexToBytesOrNull()?.takeIf { it.size == 32 } ?: return null
        val sharedX = Secp256k1.ecdhXOnly(secretKey, pubkey) ?: return null
        return hmacSha256(SALT, sharedX)
    }

    private fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val keys = hkdfExpand(conversationKey, nonce, 76)
        return Triple(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), keys.copyOfRange(44, 76))
    }

    /**
     * Encrypts [plaintext] to [pubkeyXOnlyHex] from [secretKey], with an explicit 32-byte [nonce]
     * (never reused for two different messages in real use). Null only if [pubkeyXOnlyHex] is not
     * a usable public key.
     */
    fun encrypt(secretKey: ByteArray, pubkeyXOnlyHex: String, plaintext: String, nonce: ByteArray): String? {
        require(nonce.size == NONCE_LEN) { "nonce must be $NONCE_LEN bytes" }
        val convKey = conversationKey(secretKey, pubkeyXOnlyHex) ?: return null
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(convKey, nonce)
        val ciphertext = ChaCha20.xor(chachaKey, chachaNonce, pad(plaintext.toByteArray(Charsets.UTF_8)))
        val mac = hmacSha256(hmacKey, nonce + ciphertext)
        return Base64.getEncoder().encodeToString(byteArrayOf(VERSION) + nonce + ciphertext + mac)
    }

    /** Decrypts a NIP-44 v2 [payload] from [pubkeyXOnlyHex] with [secretKey]. Null on any failure. */
    fun decrypt(secretKey: ByteArray, pubkeyXOnlyHex: String, payload: String): String? {
        val blob = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
        if (blob.size < 1 + NONCE_LEN + MAC_LEN || blob[0] != VERSION) return null
        val nonce = blob.copyOfRange(1, 1 + NONCE_LEN)
        val ciphertext = blob.copyOfRange(1 + NONCE_LEN, blob.size - MAC_LEN)
        val mac = blob.copyOfRange(blob.size - MAC_LEN, blob.size)
        val convKey = conversationKey(secretKey, pubkeyXOnlyHex) ?: return null
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(convKey, nonce)
        if (!MessageDigest.isEqual(hmacSha256(hmacKey, nonce + ciphertext), mac)) return null
        val padded = ChaCha20.xor(chachaKey, chachaNonce, ciphertext)
        return unpad(padded)?.toString(Charsets.UTF_8)
    }

    private fun pad(plaintext: ByteArray): ByteArray {
        val len = plaintext.size
        require(len in 1..0xFFFF) { "plaintext must be 1..65535 bytes" }
        val prefix = byteArrayOf((len ushr 8).toByte(), len.toByte())
        return prefix + plaintext + ByteArray(paddedLen(len) - len)
    }

    private fun unpad(padded: ByteArray): ByteArray? {
        if (padded.size < 2) return null
        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        if (len == 0 || padded.size < 2 + len) return null
        if (padded.size != 2 + paddedLen(len)) return null
        return padded.copyOfRange(2, 2 + len)
    }

    /** NIP-44's padding scheme: rounds up to a power-of-two-ish chunk so lengths cluster. */
    private fun paddedLen(len: Int): Int {
        if (len <= 32) return 32
        val nextPower = Integer.highestOneBit(len - 1) shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((len - 1) / chunk + 1)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - written)
            previous.copyInto(out, written, 0, n)
            written += n
            counter++
        }
        return out
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

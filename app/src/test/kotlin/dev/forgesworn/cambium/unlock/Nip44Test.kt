package dev.forgesworn.cambium.unlock

import dev.forgesworn.cambium.toHex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Nip44Test {

    private val sender = "aa".repeat(32).hexToBytesOrNull()!!
    private val receiver = "bb".repeat(32).hexToBytesOrNull()!!
    private val receiverPubkeyHex = Secp256k1.publicKeyXOnly(receiver).toHex()
    private val senderPubkeyHex = Secp256k1.publicKeyXOnly(sender).toHex()
    private fun nonce(b: Int) = ByteArray(32) { b.toByte() }

    @Test
    fun `a message round-trips through encrypt and decrypt`() {
        for (plaintext in listOf("hi", "heartwood-unlock:enrol?v=1&p=" + "cd".repeat(32), "x".repeat(500))) {
            val sealed = Nip44.encrypt(sender, receiverPubkeyHex, plaintext, nonce(7))
            requireNotNull(sealed)
            assertEquals(plaintext, Nip44.decrypt(receiver, senderPubkeyHex, sealed))
        }
    }

    @Test
    fun `the same plaintext and nonce always produce the same ciphertext`() {
        val a = Nip44.encrypt(sender, receiverPubkeyHex, "hello", nonce(1))
        val b = Nip44.encrypt(sender, receiverPubkeyHex, "hello", nonce(1))
        assertEquals(a, b)
    }

    @Test
    fun `a different nonce produces a different ciphertext for the same plaintext`() {
        val a = Nip44.encrypt(sender, receiverPubkeyHex, "hello", nonce(1))
        val b = Nip44.encrypt(sender, receiverPubkeyHex, "hello", nonce(2))
        assert(a != b)
    }

    @Test
    fun `a tampered payload fails to decrypt`() {
        val sealed = Nip44.encrypt(sender, receiverPubkeyHex, "hello", nonce(3))
        requireNotNull(sealed)
        val bytes = java.util.Base64.getDecoder().decode(sealed)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(bytes)
        assertNull(Nip44.decrypt(receiver, senderPubkeyHex, tampered))
    }

    @Test
    fun `decrypting with the wrong key fails`() {
        val sealed = Nip44.encrypt(sender, receiverPubkeyHex, "hello", nonce(4))
        requireNotNull(sealed)
        val wrongKey = "cc".repeat(32).hexToBytesOrNull()!!
        assertNull(Nip44.decrypt(wrongKey, senderPubkeyHex, sealed))
    }

    @Test
    fun `garbage payloads are refused, not thrown`() {
        assertNull(Nip44.decrypt(receiver, senderPubkeyHex, "not base64!!"))
        assertNull(Nip44.decrypt(receiver, senderPubkeyHex, java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 1, 2))))
    }

    @Test
    fun `encrypt refuses a malformed public key`() {
        assertNull(Nip44.encrypt(sender, "zz", "hello", nonce(5)))
        assertNull(Nip44.encrypt(sender, "ab", "hello", nonce(5)))
    }
}

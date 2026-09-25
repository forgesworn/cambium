package dev.forgesworn.cambium.unlock

import dev.forgesworn.cambium.toHex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InviteReplyTest {

    private val invSecret = "11".repeat(32).hexToBytesOrNull()!!
    private val invPubkeyHex = Secp256k1.publicKeyXOnly(invSecret).toHex()
    private val invite = InviteUri(invPubkeyHex, "cd".repeat(16), 2_000_000L, listOf("wss://relay.example"))
    private val code = EnrolmentCode("ab".repeat(32), "ef".repeat(16), "phone", listOf("wss://own.example")).encode()

    @Test
    fun `the reply carries the invite's rendezvous and expiry, and nothing else`() {
        val throwaway = "22".repeat(32).hexToBytesOrNull()!!
        val nonce = ByteArray(32) { 9 }
        val reply = InviteReplyBuilder.build(invite, code, throwaway, nonce)
        assertNotNull(reply)
        assertEquals(invite.rendezvous, reply.rendezvous)
        assertEquals(invite.expiresAtSecs, reply.expiresAtSecs)
        assertEquals(Secp256k1.publicKeyXOnly(throwaway).toHex(), reply.throwawayPubkeyHex)
    }

    @Test
    fun `the content decrypts, with inv's own secret, back to the exact enrolment code`() {
        val throwaway = "33".repeat(32).hexToBytesOrNull()!!
        val nonce = ByteArray(32) { 5 }
        val reply = InviteReplyBuilder.build(invite, code, throwaway, nonce)
        assertNotNull(reply)
        assertEquals(code, Nip44.decrypt(invSecret, reply.throwawayPubkeyHex, reply.content))
    }

    @Test
    fun `a fresh call with default randomness never reuses a throwaway key or nonce`() {
        val a = InviteReplyBuilder.build(invite, code)
        val b = InviteReplyBuilder.build(invite, code)
        assertNotNull(a)
        assertNotNull(b)
        assert(a.throwawayPubkeyHex != b.throwawayPubkeyHex)
        assert(a.content != b.content)
    }
}

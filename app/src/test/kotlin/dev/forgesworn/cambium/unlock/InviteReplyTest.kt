package dev.forgesworn.cambium.unlock

import kotlin.test.Test
import kotlin.test.assertEquals

class InviteReplyTest {

    private val invite = InviteUri("ab".repeat(32), "cd".repeat(16), 2_000_000L, listOf("wss://relay.example"))

    @Test
    fun `the reply carries exactly the invite's rendezvous and expiry, nothing else`() {
        assertEquals(
            listOf("h" to invite.rendezvous, "expiration" to "2000000"),
            InviteReplyBuilder.tags(invite),
        )
    }

    @Test
    fun `a different invite produces different tag values`() {
        val other = invite.copy(rendezvous = "ef".repeat(16), expiresAtSecs = 3_000_000L)
        assertEquals(listOf("h" to "ef".repeat(16), "expiration" to "3000000"), InviteReplyBuilder.tags(other))
    }
}

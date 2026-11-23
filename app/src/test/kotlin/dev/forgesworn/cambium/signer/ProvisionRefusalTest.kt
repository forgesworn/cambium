package dev.forgesworn.cambium.signer

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProvisionRefusalTest {
    /** Copied from heartwood-esp32 (main at 02e25ca): every refusal a relay client can receive for
     * `heartwood_provision_rendezvous`, apart from internal derivation and storage failures. */
    private val boardErrors = listOf(
        "user denied",
        "timeout",
        "unauthorised",
        "this request must be approved at the device; a guardian verdict cannot answer it",
        "unknown method",
        "trusted clock unavailable",
        "rendezvous expiry must be after signer time and within ten minutes",
        "rendezvous provision receipt store full",
        "rendezvous provision already completed",
        "rendezvous provision nonce already used",
    )
    private val messages = boardErrors.map { ProvisionRefusal.messageFor(JsonPrimitive(it)) }

    @Test fun `each board refusal gets its own message`() {
        assertEquals(boardErrors.size, messages.toSet().size)
        assertFalse(ProvisionRefusal.GENERIC in messages)
        assertEquals("Denied on Heartwood", messages[0])
        assertEquals("Heartwood refused: the button was not held in time", messages[1])
        assertEquals("Heartwood refused: turn off Approve from my phone for this connection", messages[3])
    }

    @Test fun `unknown, altered, non-string and missing errors stay generic`() {
        for (error in listOf(JsonPrimitive("rendezvous provision exploded"), JsonPrimitive("User denied"),
            JsonPrimitive(" user denied"), JsonPrimitive(-1), JsonPrimitive(true), JsonNull,
            buildJsonObject { put("message", "user denied") }, JsonArray(listOf(JsonPrimitive("user denied"))), null)) {
            assertEquals(error.toString(), ProvisionRefusal.GENERIC, ProvisionRefusal.messageFor(error))
        }
    }

    @Test fun `a refusal keeps connection health showing the board as reachable`() {
        // SessionRegistry.recordFailure keeps a signer CONNECTED only for an answer that
        // isPolicyRefusal recognises. A board that refused did answer.
        for (message in messages + ProvisionRefusal.GENERIC) {
            assertTrue(message, isPolicyRefusal(HeartwoodError.Protocol(message)))
        }
    }

    @Test fun `messages stay short enough for a two-line toast`() {
        for (message in messages) assertTrue(message, message.length <= 72)
    }
}

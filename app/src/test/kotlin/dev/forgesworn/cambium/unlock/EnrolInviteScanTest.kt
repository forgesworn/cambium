package dev.forgesworn.cambium.unlock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EnrolInviteScanTest {

    private val now = 1_000_000L
    private val k = "ab".repeat(32)
    private val r = "cd".repeat(16)
    private val good = "heartwood-unlock:invite?v=1&k=$k&r=$r&x=${now + 300}&relay=wss%3A%2F%2Frelay.example"

    @Test
    fun `a null scan is cancelled, not an error`() {
        assertEquals(EnrolInviteScanResult.Cancelled, EnrolInviteScan.evaluate(null, now))
    }

    @Test
    fun `blank content is not an invite`() {
        assertEquals(EnrolInviteScanResult.Rejected(EnrolInviteScan.NOT_AN_INVITE), EnrolInviteScan.evaluate("   ", now))
        assertEquals(EnrolInviteScanResult.Rejected(EnrolInviteScan.NOT_AN_INVITE), EnrolInviteScan.evaluate("nonsense", now))
    }

    @Test
    fun `a well-formed invite is accepted`() {
        val result = EnrolInviteScan.evaluate(good, now)
        assertIs<EnrolInviteScanResult.Accepted>(result)
        assertEquals(InviteUri(k, r, now + 300, listOf("wss://relay.example")), result.invite)
    }

    @Test
    fun `a bunker link gets a wrong-direction message`() {
        val bunker = "bunker://$k?relay=wss%3A%2F%2Frelay.example&secret=abc"
        assertEquals(EnrolInviteScanResult.Rejected(EnrolInviteScan.WRONG_DIRECTION_BUNKER), EnrolInviteScan.evaluate(bunker, now))
    }

    @Test
    fun `a nostrconnect link gets the same wrong-direction message as a bunker link`() {
        val nostrconnect = "nostrconnect://$k?relay=wss%3A%2F%2Frelay.example"
        assertEquals(EnrolInviteScanResult.Rejected(EnrolInviteScan.WRONG_DIRECTION_BUNKER), EnrolInviteScan.evaluate(nostrconnect, now))
    }

    @Test
    fun `a phone's own enrolment code gets a wrong-direction message`() {
        val enrolCode = EnrolmentCode(k, r, "phone", listOf("wss://relay.example")).encode()
        assertEquals(EnrolInviteScanResult.Rejected(EnrolInviteScan.WRONG_DIRECTION_ENROL_CODE), EnrolInviteScan.evaluate(enrolCode, now))
    }
}

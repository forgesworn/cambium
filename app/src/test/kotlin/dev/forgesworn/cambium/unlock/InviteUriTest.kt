package dev.forgesworn.cambium.unlock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InviteUriTest {

    private val k = "ab".repeat(32)
    private val r = "cd".repeat(16)
    private val now = 1_000_000L
    private val x = now + 300
    private val good = "heartwood-unlock:invite?v=1&k=$k&r=$r&x=$x&relay=wss%3A%2F%2Frelay.example&relay=wss%3A%2F%2Ftwo.example"

    @Test
    fun `a well-formed invite parses`() {
        assertEquals(
            InviteUri(k, r, x, listOf("wss://relay.example", "wss://two.example")),
            InviteUri.parse(good, now),
        )
    }

    @Test
    fun `a localhost ws relay is accepted for a test bench`() {
        val text = "heartwood-unlock:invite?v=1&k=$k&r=$r&x=$x&relay=ws%3A%2F%2Flocalhost%3A7777"
        assertEquals(listOf("ws://localhost:7777"), InviteUri.parse(text, now)?.relays)
    }

    @Test
    fun `duplicate relays are deduplicated`() {
        val text = "heartwood-unlock:invite?v=1&k=$k&r=$r&x=$x&relay=wss%3A%2F%2Frelay.example&relay=wss%3A%2F%2Frelay.example"
        assertEquals(listOf("wss://relay.example"), InviteUri.parse(text, now)?.relays)
    }

    @Test
    fun `malformed invites are refused`() {
        for (bad in listOf(
            "",
            "heartwood-unlock:enrol?v=1&p=$k&r=$r&label=x&relay=wss://a",
            good.replace("v=1", "v=2"),
            good.replace("k=$k", "k=${k.dropLast(2)}"),
            good.replace("k=$k", "k=${k.uppercase()}"),
            good.replace("r=$r", "r=${r}00"),
            good.substringBefore("&relay="),
            good.replace("wss%3A%2F%2Frelay.example", "https%3A%2F%2Frelay.example"),
            "$good&k=$k",
            good.replace("x=$x", "x=notanumber"),
        )) {
            assertNull(InviteUri.parse(bad, now), bad)
        }
    }

    @Test
    fun `an expired or too-distant expiry is refused`() {
        assertNull(InviteUri.parse(good.replace("x=$x", "x=$now"), now), "already expired")
        assertNull(InviteUri.parse(good.replace("x=$x", "x=${now - 1}"), now), "in the past")
        val tooFar = now + InviteUri.MAX_FUTURE_SECS + 1
        assertNull(InviteUri.parse(good.replace("x=$x", "x=$tooFar"), now), "too far ahead")
        val justInTime = now + InviteUri.MAX_FUTURE_SECS
        assertEquals(justInTime, InviteUri.parse(good.replace("x=$x", "x=$justInTime"), now)?.expiresAtSecs)
    }
}

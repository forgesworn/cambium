package dev.forgesworn.cambium.unlock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EnrolmentTest {

    private val p = "ab".repeat(32)
    private val r = "cd".repeat(16)
    private val code = EnrolmentCode(p, r, "Pixel 8 Pro", listOf("wss://relay.example", "wss://two.example/path?x=1"))

    @Test
    fun `an enrolment code survives the round trip`() {
        val text = code.encode()
        assertEquals("heartwood-unlock:enrol?v=1&p=$p&r=$r&label=Pixel+8+Pro&relay=wss%3A%2F%2Frelay.example" +
            "&relay=wss%3A%2F%2Ftwo.example%2Fpath%3Fx%3D1", text)
        assertEquals(code, EnrolmentCode.parse(text))
    }

    @Test
    fun `malformed codes are refused`() {
        val good = code.encode()
        for (bad in listOf(
            "",
            "bunker://$p?relay=wss://x",
            good.replace("v=1", "v=2"),
            good.replace("p=$p", "p=${p.dropLast(2)}"),
            good.replace("p=$p", "p=${p.uppercase()}"),
            good.replace("r=$r", "r=${r}00"),
            good.replace("label=Pixel+8+Pro", "label="),
            good.replace("label=Pixel+8+Pro", "label=" + "x".repeat(17)),
            good.substringBefore("&relay="),
            good.replace("wss%3A%2F%2Frelay.example", "https%3A%2F%2Frelay.example"),
            "$good&p=$p",
        )) {
            assertNull(EnrolmentCode.parse(bad), bad)
        }
    }

    @Test
    fun `labels are cut to the board's 16 bytes without splitting a character`() {
        assertEquals("Pixel 8", EnrolmentCode.fitLabel("  Pixel 8  "))
        assertEquals("phone", EnrolmentCode.fitLabel(" "))
        assertEquals("abcdefghijklmnop", EnrolmentCode.fitLabel("abcdefghijklmnopqrst"))
        // "é" is two bytes: seven of them fill 14, an eighth fits exactly, a ninth does not.
        assertEquals("é".repeat(8), EnrolmentCode.fitLabel("é".repeat(9)))
        // A four-byte emoji at the boundary is dropped whole.
        val cut = EnrolmentCode.fitLabel("abcdefghijklmn😀")
        assertEquals("abcdefghijklmn", cut)
    }

    @Test
    fun `the hand-off envelope is the board's answer passed through`() {
        val envelope = HandOffParser.envelope("{\"id\":7,\"ephemeral_pubkey\":\"$p\",\"sealed\":\"AgQ=\"}")
        assertEquals(HandOffEnvelope(7, p, "AgQ="), envelope)
        assertNull(HandOffParser.envelope("{\"id\":7,\"ephemeral_pubkey\":\"zz\",\"sealed\":\"AgQ=\"}"))
        assertNull(HandOffParser.envelope("{\"id\":-1,\"ephemeral_pubkey\":\"$p\",\"sealed\":\"AgQ=\"}"))
        assertNull(HandOffParser.envelope("{\"id\":7,\"ephemeral_pubkey\":\"$p\",\"sealed\":\"\"}"))
        assertNull(HandOffParser.envelope("not json"))
    }

    @Test
    fun `the opened hand-off must be the one announced`() {
        val envelope = HandOffEnvelope(7, p, "x")
        val s = "5a".repeat(32)
        val ok = HandOffParser.plain("{\"v\":1,\"id\":7,\"s\":\"$s\",\"relays\":[\"wss://relay.example\",\"https://no\"]}", envelope)
        assertNotNull(ok)
        assertEquals(listOf("wss://relay.example"), ok.relays)
        assertEquals(0x5a.toByte(), ok.slotSecret[31])
        assertNull(HandOffParser.plain("{\"v\":1,\"id\":8,\"s\":\"$s\",\"relays\":[\"wss://a\"]}", envelope), "id mismatch")
        assertNull(HandOffParser.plain("{\"v\":2,\"id\":7,\"s\":\"$s\",\"relays\":[\"wss://a\"]}", envelope), "version")
        assertNull(HandOffParser.plain("{\"v\":1,\"id\":7,\"s\":\"abab\",\"relays\":[\"wss://a\"]}", envelope), "short secret")
        assertNull(HandOffParser.plain("{\"v\":1,\"id\":7,\"s\":\"$s\",\"relays\":[]}", envelope), "no relays")
    }
}

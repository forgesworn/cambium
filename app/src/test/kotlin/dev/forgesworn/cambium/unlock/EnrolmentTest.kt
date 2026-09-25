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
    fun `labels are cut to the board's 16 bytes`() {
        assertEquals("Pixel 8", EnrolmentCode.fitLabel("  Pixel 8  "))
        assertEquals("phone", EnrolmentCode.fitLabel(" "))
        assertEquals("abcdefghijklmnop", EnrolmentCode.fitLabel("abcdefghijklmnopqrst"))
    }

    /**
     * The board refuses any label that is not printable ASCII (0x20-0x7E), on the cable too, so
     * `fitLabel` must never hand it anything else.
     */
    @Test
    fun `labels are reduced to printable ASCII`() {
        // An accented letter decomposes to its plain ASCII base rather than being dropped.
        assertEquals("e".repeat(9), EnrolmentCode.fitLabel("é".repeat(9)))
        assertEquals("Zoe's phone", EnrolmentCode.fitLabel("Zoë's phone"))
        // Fullwidth Latin characters reduce to ordinary ASCII the same way.
        assertEquals("swim", EnrolmentCode.fitLabel("ｓｗｉｍ"))
        // A four-byte emoji has no ASCII equivalent and is dropped whole.
        assertEquals("abcdefghijklmn", EnrolmentCode.fitLabel("abcdefghijklmn😀"))
        // Control characters, separators and zero-width characters are all dropped, never passed through.
        assertEquals("xswim behind", EnrolmentCode.fitLabel("x\nswim behind"))
        assertEquals("nul", EnrolmentCode.fitLabel("nul\u0000"))
        assertEquals("del", EnrolmentCode.fitLabel("del\u007f"))
        assertEquals("nelx", EnrolmentCode.fitLabel("nel\u0085x"))
        assertEquals("x", EnrolmentCode.fitLabel(" x"))
        assertEquals("swim", EnrolmentCode.fitLabel("​swim"))
        // A label built entirely of non-ASCII input still falls back rather than coming back empty.
        assertEquals("phone", EnrolmentCode.fitLabel("あいう"))
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

    /** Vectors from spoken-token 2.1.0 itself: deriveToken(key, 'heartwood-unlock:enrol-check', 0, hex 6). */
    @Test
    fun `the check code is spoken-token's six-character hex token of the hand-off key`() {
        assertEquals("9B6 164", checkCode("ab".repeat(32)))
        assertEquals("EF1 645", checkCode("00".repeat(32)))
        assertNull(checkCode("abcd"))
    }

    /**
     * Frozen vectors from heartwood-esp32's `request_code`/`request_words` tests -- the board's
     * request code and Cambium's must match byte for byte.
     */
    @Test
    fun `the request words match the board's frozen vectors`() {
        assertEquals("swim behind stand bugle", requestWords("ab".repeat(32)))
        assertEquals("talent humble reform admit", requestWords("00".repeat(32)))
        assertEquals("profit buddy moment aim", requestWords("42".repeat(32)))
        assertEquals("what attitude price easy", requestWords("ff".repeat(32)))
        assertNull(requestWords("abcd"))
    }
}

package dev.forgesworn.cambium.unlock

import dev.forgesworn.cambium.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneUnlockTest {

    private fun ctx(t: String = PhoneUnlock.TYPE_LOCKED) = LockContext(
        v = 1,
        t = t,
        id = 7,
        boot = 212,
        reset = "poweron",
        ssid = "devolo-753",
        bssid = "aa:bb:cc:dd:ee:ff",
        fw = "0.18.0-beta.17",
        relays = listOf("wss://relay.example", "wss://two.example"),
    )

    private fun json(context: LockContext) = Json.encodeToString(LockContext.serializer(), context)
    private fun bytes(value: Int, n: Int = 32) = ByteArray(n) { value.toByte() }

    @Test
    fun `ChaCha20 matches RFC 8439 section 2_4_2`() {
        // The RFC's example starts at block counter 1; ours starts at 0, so encrypt one zero block first.
        val key = ByteArray(32) { it.toByte() }
        val nonce = "000000000000004a00000000".hexToBytesOrNull()!!
        val plain = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        val out = ChaCha20.xor(key, nonce, ByteArray(64) + plain.toByteArray())
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0bf91b65c5524733ab8f593dabcd62b357" +
                "1639d624e65152ab8f530c359f0861d807ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                "5af90bbf74a35be6b40b8eedf2785e42874d",
            out.copyOfRange(64, out.size).toHex(),
        )
    }

    /** The firmware's published vectors (heartwood-esp32 common/tests/fixtures/phone-unlock-v1.json). */
    @Test
    fun `the firmware vectors hold`() {
        val text = javaClass.classLoader!!.getResource("phone-unlock-v1.json")!!.readText()
        val fixture = Json.parseToJsonElement(text) as JsonObject
        fun field(name: String) = fixture[name]!!.jsonPrimitive.content
        val s = field("slot_secret").hexToBytesOrNull()!!
        val k = PhoneUnlock.phoneKey(s)
        assertEquals(field("phone_key"), k.toHex())
        val author = field("author").hexToBytesOrNull()!!
        assertEquals(field("hint"), PhoneUnlock.hint(k, author))
        val context = Json.decodeFromJsonElement(LockContext.serializer(), fixture["context"]!!)
        assertEquals(ctx(), context)
        val sealed = PhoneUnlock.sealContext(k, author, json(context), field("nonce").hexToBytesOrNull()!!)
        assertEquals(field("content"), sealed)
        assertEquals(context, PhoneUnlock.openContext(k, author, field("content")))
        assertEquals(field("delivery"), PhoneUnlock.deliveryJson(context.id, s))
    }

    /**
     * The firmware's relay-update vector (heartwood-esp32
     * `common/tests/fixtures/phone-unlock-v1-relays.json`): identical construction to a lock
     * announcement, only `t` differs. A phone opens it exactly the same way and never prompts.
     */
    @Test
    fun `the firmware relay-update vector opens and is never a prompt`() {
        val text = javaClass.classLoader!!.getResource("phone-unlock-v1-relays.json")!!.readText()
        val fixture = Json.parseToJsonElement(text) as JsonObject
        fun field(name: String) = fixture[name]!!.jsonPrimitive.content
        val s = field("slot_secret").hexToBytesOrNull()!!
        val k = PhoneUnlock.phoneKey(s)
        assertEquals(field("phone_key"), k.toHex())
        val author = field("author").hexToBytesOrNull()!!
        assertEquals(field("hint"), PhoneUnlock.hint(k, author))
        val context = Json.decodeFromJsonElement(LockContext.serializer(), fixture["context"]!!)
        assertEquals(ctx(PhoneUnlock.TYPE_RELAYS), context)
        assertEquals(PhoneUnlock.TYPE_RELAYS, context.t)
        val sealed = PhoneUnlock.sealContext(k, author, json(context), field("nonce").hexToBytesOrNull()!!)
        assertEquals(field("content"), sealed)
        assertEquals(context, PhoneUnlock.openContext(k, author, field("content")))
        assertEquals(
            Verdict.NOT_LOCKED,
            PhoneUnlock.judge(context, author.toHex(), 1_800_000_000L, 1_800_000_000L, null),
        )
    }

    @Test
    fun `a phone recognises and opens its own announcement only`() {
        val k = PhoneUnlock.phoneKey(bytes(1))
        val other = PhoneUnlock.phoneKey(bytes(2))
        val author = bytes(9)
        val h = PhoneUnlock.hint(k, author)
        assertEquals(16, h.length)
        assertTrue(PhoneUnlock.hintMatches(k, author, h))
        assertFalse(PhoneUnlock.hintMatches(other, author, h))
        assertFalse(PhoneUnlock.hintMatches(k, bytes(8), h), "the hint changes with the one-time key")
        assertFalse(PhoneUnlock.hintMatches(k, author, h.substring(0, 15)))

        val content = PhoneUnlock.sealContext(k, author, json(ctx()), bytes(3, 12))
        assertEquals(ctx(), PhoneUnlock.openContext(k, author, content))
        assertNull(PhoneUnlock.openContext(other, author, content))
        assertNull(PhoneUnlock.openContext(k, bytes(8), content), "content is bound to its author")
        assertFalse("devolo" in content)
    }

    @Test
    fun `tampered or malformed content is refused`() {
        val k = PhoneUnlock.phoneKey(bytes(1))
        val author = bytes(9)
        val raw = Base64.getDecoder().decode(PhoneUnlock.sealContext(k, author, json(ctx()), bytes(3, 12)))
        for (i in raw.indices) {
            raw[i] = (raw[i].toInt() xor 1).toByte()
            assertNull(PhoneUnlock.openContext(k, author, Base64.getEncoder().encodeToString(raw)), "flip at $i")
            raw[i] = (raw[i].toInt() xor 1).toByte()
        }
        assertNull(PhoneUnlock.openContext(k, author, "not base64!"))
        assertNull(PhoneUnlock.openContext(k, author, ""))
        assertNull(PhoneUnlock.openContext(k, author, "A".repeat(PhoneUnlock.MAX_CONTENT_LEN + 4)))
        // A valid seal whose plaintext is not a context, and one of a future version.
        assertNull(PhoneUnlock.openContext(k, author, PhoneUnlock.sealContext(k, author, "{\"v\":1}", bytes(3, 12))))
        val v2 = json(ctx()).replace("\"v\":1", "\"v\":2")
        assertNull(PhoneUnlock.openContext(k, author, PhoneUnlock.sealContext(k, author, v2, bytes(3, 12))))
    }

    @Test
    fun `a context with fields added later still opens`() {
        val k = PhoneUnlock.phoneKey(bytes(1))
        val author = bytes(9)
        val extended = json(ctx()).dropLast(1) + ",\"new\":true}"
        assertEquals(ctx(), PhoneUnlock.openContext(k, author, PhoneUnlock.sealContext(k, author, extended, bytes(3, 12))))
    }

    @Test
    fun `the prompt rule matches the firmware's`() {
        val c = ctx()
        val a = bytes(9).toHex()
        val b = bytes(8).toHex()
        val now = 1_800_000_000L
        assertEquals(Verdict.PROMPT, PhoneUnlock.judge(c, a, now, now, null))
        assertEquals(Verdict.PROMPT, PhoneUnlock.judge(c, a, now - 120, now, LastPrompt(211, b)))
        assertEquals(Verdict.STALE, PhoneUnlock.judge(c, a, now - 121, now, null))
        assertEquals(Verdict.PROMPT, PhoneUnlock.judge(c, a, now + 60, now, null))
        assertEquals(Verdict.STALE, PhoneUnlock.judge(c, a, now + 61, now, null))
        assertEquals(Verdict.DUPLICATE, PhoneUnlock.judge(c, a, now, now, LastPrompt(212, a)))
        assertEquals(Verdict.PROMPT, PhoneUnlock.judge(c, a, now, now, LastPrompt(212, b)), "same count, new boot")
        assertEquals(Verdict.REPLAY, PhoneUnlock.judge(c, a, now, now, LastPrompt(213, a)))
        assertEquals(Verdict.NOT_LOCKED, PhoneUnlock.judge(ctx(PhoneUnlock.TYPE_RELAYS), a, now, now, null))
    }

    @Test
    fun `hex decoding refuses anything but hex pairs`() {
        assertContentEquals(byteArrayOf(0x0a, -1), "0aFF".hexToBytesOrNull())
        assertNull("abc".hexToBytesOrNull())
        assertNull("zz".hexToBytesOrNull())
    }
}

package dev.forgesworn.cambium.nip55

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class EventJsonTest {

    private val signedEvent = """
        {"id":"a1b2","pubkey":"c3d4","created_at":1720000000,"kind":31234,
         "tags":[["d","draft-slug"]],"content":"ciphertext",
         "sig":"deadbeef00deadbeef00deadbeef00deadbeef00deadbeef00deadbeef00dead"}
    """.trimIndent()

    // -- extractEventKind --

    @Test
    fun `extracts an integer kind`() {
        assertEquals(31234, extractEventKind(signedEvent))
    }

    @Test
    fun `a string-typed kind still parses`() {
        // Parity with the previous org.json behaviour, which coerced "1" to 1 -- a client quirk
        // the NIP-37 draft decline must keep recognising.
        assertEquals(31234, extractEventKind("""{"kind":"31234"}"""))
    }

    @Test
    fun `a missing kind is null`() {
        assertNull(extractEventKind("""{"content":"no kind here"}"""))
    }

    @Test
    fun `a JSON-null kind is null, not an exception`() {
        assertNull(extractEventKind("""{"kind":null}"""))
    }

    @Test
    fun `a non-integer kind is null, not an exception`() {
        assertNull(extractEventKind("""{"kind":["not","an","int"]}"""))
    }

    @Test
    fun `malformed json is null, not an exception`() {
        assertNull(extractEventKind("not json at all"))
        assertNull(extractEventKind(""))
    }

    @Test
    fun `a JSON array at the top level is null, not an exception`() {
        assertNull(extractEventKind("""[{"kind":1}]"""))
    }

    // -- extractEventSignatureHex --

    @Test
    fun `extracts the signature`() {
        assertEquals(
            "deadbeef00deadbeef00deadbeef00deadbeef00deadbeef00deadbeef00dead",
            extractEventSignatureHex(signedEvent),
        )
    }

    @Test
    fun `an unsigned event has no signature`() {
        assertNull(extractEventSignatureHex("""{"kind":1,"content":"unsigned"}"""))
    }

    @Test
    fun `a JSON-null sig is null, not an exception`() {
        assertNull(extractEventSignatureHex("""{"kind":1,"sig":null}"""))
    }

    @Test
    fun `a structured sig value is null, not an exception`() {
        assertNull(extractEventSignatureHex("""{"kind":1,"sig":{"nested":"object"}}"""))
    }

    @Test
    fun `malformed json has no signature`() {
        assertNull(extractEventSignatureHex("{truncated"))
    }

    // -- normaliseUnsignedEvent --

    @Test
    fun `a missing created_at is defaulted, other fields untouched`() {
        // Primal's login sign_event arrives exactly like this: no created_at at all.
        val withDefault = normaliseUnsignedEvent(
            """{"pubkey":"c3d4","tags":[],"kind":30078,"content":"{}"}""",
            nowEpochSeconds = 1720000123,
        )
        val obj = Json.parseToJsonElement(withDefault).jsonObject
        assertEquals(1720000123, obj["created_at"]?.jsonPrimitive?.long)
        assertEquals(30078, obj["kind"]?.jsonPrimitive?.long?.toInt())
        assertEquals("c3d4", obj["pubkey"]?.jsonPrimitive?.content)
    }

    @Test
    fun `missing tags are defaulted to an empty array`() {
        // Primal's wallet-operation sign_event arrives like this: no tags field at all.
        val withDefault = normaliseUnsignedEvent(
            """{"pubkey":"c3d4","created_at":1720000000,"kind":10000300,"content":"{}"}""",
            nowEpochSeconds = 1720000123,
        )
        val obj = Json.parseToJsonElement(withDefault).jsonObject
        assertEquals("[]", obj["tags"]?.toString())
        assertEquals(1720000000, obj["created_at"]?.jsonPrimitive?.long)
    }

    @Test
    fun `an event missing both fields gains both`() {
        val withDefaults = normaliseUnsignedEvent(
            """{"pubkey":"c3d4","kind":1,"content":"hello"}""",
            nowEpochSeconds = 42,
        )
        val obj = Json.parseToJsonElement(withDefaults).jsonObject
        assertEquals(42, obj["created_at"]?.jsonPrimitive?.long)
        assertEquals("[]", obj["tags"]?.toString())
    }

    @Test
    fun `a complete event is never rewritten`() {
        val untouched = normaliseUnsignedEvent(signedEvent, nowEpochSeconds = 9999999999)
        assertEquals(signedEvent, untouched)
    }

    @Test
    fun `malformed json passes through unchanged for rust-nostr to reject`() {
        assertEquals("not json at all", normaliseUnsignedEvent("not json at all", nowEpochSeconds = 1))
        assertEquals("""[{"kind":1}]""", normaliseUnsignedEvent("""[{"kind":1}]""", nowEpochSeconds = 1))
    }
}

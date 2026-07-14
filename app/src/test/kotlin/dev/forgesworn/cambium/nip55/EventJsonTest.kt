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

    // -- ensureCreatedAt --

    @Test
    fun `a missing created_at is defaulted, other fields untouched`() {
        // Primal's login sign_event arrives exactly like this: no created_at at all.
        val withDefault = ensureCreatedAt(
            """{"pubkey":"c3d4","tags":[],"kind":30078,"content":"{}"}""",
            nowEpochSeconds = 1720000123,
        )
        val obj = Json.parseToJsonElement(withDefault).jsonObject
        assertEquals(1720000123, obj["created_at"]?.jsonPrimitive?.long)
        assertEquals(30078, obj["kind"]?.jsonPrimitive?.long?.toInt())
        assertEquals("c3d4", obj["pubkey"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a present created_at is never overwritten`() {
        val untouched = ensureCreatedAt(signedEvent, nowEpochSeconds = 9999999999)
        assertEquals(signedEvent, untouched)
    }

    @Test
    fun `malformed json passes through unchanged for rust-nostr to reject`() {
        assertEquals("not json at all", ensureCreatedAt("not json at all", nowEpochSeconds = 1))
        assertEquals("""[{"kind":1}]""", ensureCreatedAt("""[{"kind":1}]""", nowEpochSeconds = 1))
    }
}

package dev.forgesworn.cambium.nip55

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}

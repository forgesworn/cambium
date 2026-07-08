package dev.forgesworn.cambium.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BunkerUriTest {

    private val pubkey = "a".repeat(64)

    @Test
    fun `parses a valid uri with one relay and a secret`() {
        val result = BunkerUriParser.parse("bunker://$pubkey?relay=wss://relay.example&secret=cafe1234")

        val valid = assertIs<BunkerUriResult.Valid>(result)
        assertEquals(pubkey, valid.uri.signerPubkeyHex)
        assertEquals(listOf("wss://relay.example"), valid.uri.relays)
        assertEquals("cafe1234", valid.uri.secret)
    }

    @Test
    fun `collects and dedupes multiple relays in order`() {
        val uri = "bunker://$pubkey" +
            "?relay=wss://one.example" +
            "&relay=wss://two.example" +
            "&relay=wss://one.example"

        val valid = assertIs<BunkerUriResult.Valid>(BunkerUriParser.parse(uri))
        assertEquals(listOf("wss://one.example", "wss://two.example"), valid.uri.relays)
    }

    @Test
    fun `secret is optional`() {
        val valid = assertIs<BunkerUriResult.Valid>(
            BunkerUriParser.parse("bunker://$pubkey?relay=wss://relay.example")
        )
        assertNull(valid.uri.secret)
    }

    @Test
    fun `rejects a public key that is not 64 hex characters`() {
        val tooShort = BunkerUriParser.parse("bunker://abc123?relay=wss://relay.example")
        assertIs<BunkerUriResult.Invalid>(tooShort)

        val notHex = BunkerUriParser.parse("bunker://${"z".repeat(64)}?relay=wss://relay.example")
        assertIs<BunkerUriResult.Invalid>(notHex)
    }

    @Test
    fun `drops non-wss relays but keeps valid ones`() {
        val uri = "bunker://$pubkey" +
            "?relay=ws://insecure.example" +
            "&relay=wss://secure.example" +
            "&relay=http://not-a-relay.example"

        val valid = assertIs<BunkerUriResult.Valid>(BunkerUriParser.parse(uri))
        assertEquals(listOf("wss://secure.example"), valid.uri.relays)
    }

    @Test
    fun `fails when every relay is non-wss`() {
        val uri = "bunker://$pubkey?relay=ws://insecure.example"
        assertIs<BunkerUriResult.Invalid>(BunkerUriParser.parse(uri))
    }

    @Test
    fun `fails when there are no relays at all`() {
        val uri = "bunker://$pubkey?secret=cafe1234"
        assertIs<BunkerUriResult.Invalid>(BunkerUriParser.parse(uri))
    }

    @Test
    fun `accepts an uppercase scheme`() {
        val valid = assertIs<BunkerUriResult.Valid>(
            BunkerUriParser.parse("BUNKER://$pubkey?relay=wss://relay.example")
        )
        assertEquals(pubkey, valid.uri.signerPubkeyHex)
    }

    @Test
    fun `normalises an uppercase hex public key to lowercase`() {
        val valid = assertIs<BunkerUriResult.Valid>(
            BunkerUriParser.parse("bunker://${pubkey.uppercase()}?relay=wss://relay.example")
        )
        assertEquals(pubkey, valid.uri.signerPubkeyHex)
    }

    @Test
    fun `rejects a uri with the wrong scheme`() {
        assertIs<BunkerUriResult.Invalid>(
            BunkerUriParser.parse("nostrconnect://$pubkey?relay=wss://relay.example")
        )
    }

    @Test
    fun `rejects an empty string`() {
        assertIs<BunkerUriResult.Invalid>(BunkerUriParser.parse(""))
    }

    @Test
    fun `rejects a uri missing the public key`() {
        assertIs<BunkerUriResult.Invalid>(BunkerUriParser.parse("bunker://?relay=wss://relay.example"))
    }

    @Test
    fun `decodes url-encoded relay and secret values`() {
        val uri = "bunker://$pubkey?relay=wss%3A%2F%2Frelay.example%2Fpath&secret=hello%20world"
        val valid = assertIs<BunkerUriResult.Valid>(BunkerUriParser.parse(uri))
        assertEquals(listOf("wss://relay.example/path"), valid.uri.relays)
        assertEquals("hello world", valid.uri.secret)
    }

    @Test
    fun `round trips through toUriString and back`() {
        val original = assertIs<BunkerUriResult.Valid>(
            BunkerUriParser.parse("bunker://$pubkey?relay=wss://one.example&relay=wss://two.example&secret=cafe1234")
        ).uri

        val reparsed = assertIs<BunkerUriResult.Valid>(BunkerUriParser.parse(original.toUriString())).uri
        assertEquals(original, reparsed)
    }

    @Test
    fun `ignores a trailing path segment after the public key`() {
        val valid = assertIs<BunkerUriResult.Valid>(
            BunkerUriParser.parse("bunker://$pubkey/?relay=wss://relay.example")
        )
        assertEquals(pubkey, valid.uri.signerPubkeyHex)
    }
}

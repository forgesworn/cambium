package dev.forgesworn.cambium.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QrPairingScanTest {

    private val pubkey = "b".repeat(64)

    @Test
    fun `accepts a valid bunker uri`() {
        val result = QrPairingScan.evaluate("bunker://$pubkey?relay=wss://relay.example")

        val accepted = assertIs<QrScanResult.Accepted>(result)
        assertEquals(pubkey, accepted.uri.signerPubkeyHex)
    }

    @Test
    fun `rejects a nostrconnect uri with a distinct wrong-direction message`() {
        val result = QrPairingScan.evaluate("nostrconnect://$pubkey?relay=wss://relay.example")

        val rejected = assertIs<QrScanResult.Rejected>(result)
        assertEquals(QrPairingScan.WRONG_DIRECTION, rejected.message)
    }

    @Test
    fun `rejects an unrelated url with the generic message`() {
        val result = QrPairingScan.evaluate("https://example.com")

        val rejected = assertIs<QrScanResult.Rejected>(result)
        assertEquals(QrPairingScan.NOT_A_BUNKER_LINK, rejected.message)
    }

    @Test
    fun `rejects garbage content with the generic message`() {
        val result = QrPairingScan.evaluate("not a url at all, just some text")

        val rejected = assertIs<QrScanResult.Rejected>(result)
        assertEquals(QrPairingScan.NOT_A_BUNKER_LINK, rejected.message)
    }

    @Test
    fun `rejects an empty scan result rather than treating it as cancelled`() {
        val result = QrPairingScan.evaluate("")

        val rejected = assertIs<QrScanResult.Rejected>(result)
        assertEquals(QrPairingScan.NOT_A_BUNKER_LINK, rejected.message)
    }

    @Test
    fun `rejects a blank (whitespace-only) scan result`() {
        val result = QrPairingScan.evaluate("   ")
        assertIs<QrScanResult.Rejected>(result)
    }

    @Test
    fun `treats a null result as cancelled, not an error`() {
        val result = QrPairingScan.evaluate(null)
        assertIs<QrScanResult.Cancelled>(result)
    }

    @Test
    fun `is case-insensitive when detecting the nostrconnect scheme`() {
        val result = QrPairingScan.evaluate("NOSTRCONNECT://$pubkey?relay=wss://relay.example")

        val rejected = assertIs<QrScanResult.Rejected>(result)
        assertEquals(QrPairingScan.WRONG_DIRECTION, rejected.message)
    }

    @Test
    fun `trims surrounding whitespace before evaluating`() {
        val result = QrPairingScan.evaluate("  bunker://$pubkey?relay=wss://relay.example  ")
        assertIs<QrScanResult.Accepted>(result)
    }
}

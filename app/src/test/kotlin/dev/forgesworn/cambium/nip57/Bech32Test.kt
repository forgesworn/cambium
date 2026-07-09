package dev.forgesworn.cambium.nip57

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Bech32Test {

    // Verbatim from DIP-03 (https://github.com/damus-io/dips/blob/master/03.md), the "anon" tag
    // of its example private zap request event -- real bech32 data from the spec itself, not a
    // vector I invented, so a successful decode is real external validation of the charset and
    // checksum constants, not just internal self-consistency with this file's own encode().
    private val dip03PzapPart = "pzap1n0pkup9fxc9w3yd2tvfr03shffrapfz8rtzu9kkq6v222jw5rgtp9myyh378gdtwptpnls8f0rv0v2dyapgt7sssu4263puepgshsj9g4u9y5lvfv9fsujlgvywsuejvftlfzcanu5fmnf2a3grlelwe8v0z4mdkyhr9mddxpswtvp7mtlc4acdys7740t0x5ej36qs5amfzwz5dpwlaf4gsl69lzhqdgc3hgt62xw4y8384a6zvsnf96l3ardkd2vkk6cm77p6v7ul3gwgjr7tra7uzpkvf4hncxp5qd75h6cdadf6n2d7edhc3dyyy7qpdka2mgqhvckhzhd2gcaux34jyw6qfk3nxhaaqs6pqkuy6z34wu2p2fvqqvg55eyqlrndjlgekm7xu08lqc3g0nje59uqu0adqerv2puypez3eck9xzupg4vxyfclk37qfqxra8nt4tk9ydc2tzhpnl4wpf7jf2nrkchknfnfgmezfyqe074dexe5mkxgw67j7zn8s24tae8tml747qnq0edw5jxsx6xfc4qhshf3man0s5duw6wm63ue8fese8c7hanqzphjna3g0ee4jgpwceqzk9jgrvf9rnkt89tkvh75qm65nvtqpud30vecwlqzdlu9fhcaj7jv89gpy32y2k828vsj7x8hmlq55rleeq23e062apenymv96tkvltv266ww6kly2q2t7k6z"
    private val dip03IvPart = "iv189a0s9afn7ehz4gpeanueh56cv6t79qk"

    @Test
    fun `decodes the real DIP-03 iv part to exactly 16 bytes (an AES-CBC IV)`() {
        val decoded = Bech32.decode(dip03IvPart, expectedHrp = "iv").getOrThrow()
        assertEquals(16, decoded.size)
    }

    @Test
    fun `decodes the real DIP-03 pzap part to a whole number of AES blocks`() {
        val decoded = Bech32.decode(dip03PzapPart, expectedHrp = "pzap").getOrThrow()
        assertTrue(dip03PzapPart.length > 90, "fixture should itself exceed the BIP-173 90-char recommendation")
        assertEquals(0, decoded.size % 16, "AES-CBC ciphertext must be a whole number of 16-byte blocks")
    }

    @Test
    fun `rejects the real DIP-03 pzap part under the wrong expected hrp`() {
        assertTrue(Bech32.decode(dip03PzapPart, expectedHrp = "iv").isFailure)
    }

    @Test
    fun `rejects a corrupted checksum`() {
        val corrupted = dip03IvPart.dropLast(1) + if (dip03IvPart.last() == 'q') "p" else "q"
        assertTrue(Bech32.decode(corrupted, expectedHrp = "iv").isFailure)
    }

    @Test
    fun `rejects mixed-case input`() {
        val mixedCase = dip03IvPart.replaceFirstChar { it.uppercaseChar() }
        assertTrue(Bech32.decode(mixedCase, expectedHrp = "iv").isFailure)
    }

    @Test
    fun `rejects input with no separator`() {
        assertTrue(Bech32.decode("nosuchseparatorhere", expectedHrp = "iv").isFailure)
    }

    @Test
    fun `round trips arbitrary byte lengths through encode and decode`() {
        val random = Random(seed = 42)
        for (length in listOf(0, 1, 15, 16, 17, 32, 100, 200)) {
            val original = random.nextBytes(length)
            val encoded = Bech32.encode("test", original)
            val decoded = Bech32.decode(encoded, expectedHrp = "test").getOrThrow()
            assertTrue(original.contentEquals(decoded), "round trip failed for length $length")
        }
    }

    @Test
    fun `encode output well exceeds 90 characters for a realistic ciphertext length and still decodes`() {
        val original = Random(seed = 7).nextBytes(160)
        val encoded = Bech32.encode("pzap", original)
        assertTrue(encoded.length > 90)
        val decoded = Bech32.decode(encoded, expectedHrp = "pzap").getOrThrow()
        assertTrue(original.contentEquals(decoded))
    }

    @Test
    fun `does not accept an hrp other than the one it was encoded with`() {
        val encoded = Bech32.encode("pzap", byteArrayOf(1, 2, 3))
        assertFalse(Bech32.decode(encoded, expectedHrp = "iv").isSuccess)
    }
}

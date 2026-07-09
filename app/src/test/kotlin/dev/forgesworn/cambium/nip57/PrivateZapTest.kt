package dev.forgesworn.cambium.nip57

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PrivateZapTest {

    // Both fixtures below are copied verbatim from DIP-03's worked example
    // (https://github.com/damus-io/dips/blob/master/03.md, "Example Private Zap Request Event"
    // and "Example Private Zap Event") -- real production data, not invented test vectors.

    private val privateZapAnonTagValue =
        "pzap1n0pkup9fxc9w3yd2tvfr03shffrapfz8rtzu9kkq6v222jw5rgtp9myyh378gdtwptpnls8f0rv0v2dyapgt7sssu4263puepgshsj9g4u9y5lvfv9fsujlgvywsuejvftlfzcanu5fmnf2a3grlelwe8v0z4mdkyhr9mddxpswtvp7mtlc4acdys7740t0x5ej36qs5amfzwz5dpwlaf4gsl69lzhqdgc3hgt62xw4y8384a6zvsnf96l3ardkd2vkk6cm77p6v7ul3gwgjr7tra7uzpkvf4hncxp5qd75h6cdadf6n2d7edhc3dyyy7qpdka2mgqhvckhzhd2gcaux34jyw6qfk3nxhaaqs6pqkuy6z34wu2p2fvqqvg55eyqlrndjlgekm7xu08lqc3g0nje59uqu0adqerv2puypez3eck9xzupg4vxyfclk37qfqxra8nt4tk9ydc2tzhpnl4wpf7jf2nrkchknfnfgmezfyqe074dexe5mkxgw67j7zn8s24tae8tml747qnq0edw5jxsx6xfc4qhshf3man0s5duw6wm63ue8fese8c7hanqzphjna3g0ee4jgpwceqzk9jgrvf9rnkt89tkvh75qm65nvtqpud30vecwlqzdlu9fhcaj7jv89gpy32y2k828vsj7x8hmlq55rleeq23e062apenymv96tkvltv266ww6kly2q2t7k6z_iv189a0s9afn7ehz4gpeanueh56cv6t79qk"

    private val privateZapRequestEventJson = """
        {
          "id": "4ef9df0a6c7dd8b761145acc7055ae077df716bd8c374496d1f598c5b63bcd7a",
          "pubkey": "79d4773fded68a3ea9a30f13e651ff83e150957dacb0c2f6038883d837e1b17b",
          "created_at": 1708466564,
          "kind": 9734,
          "tags": [
            [
              "p",
              "aa4fc8665f5696e33db7e1a572e3b0f5b3d615837b0f362dcb1c8068b098c7b4"
            ],
            [
              "relays",
              "wss://relay.damus.io"
            ],
            [
              "anon",
              "$privateZapAnonTagValue"
            ]
          ],
          "content": "",
          "sig": "95984f4403a4f414436270584a2ea1e3b83c988a0794ed8438f7d214714de3d982edf5eaf494362333ee07b2ce49c64c1d1206ec3914e9c7d7d4bf958d8bbfad"
        }
    """.trimIndent()

    private val decryptedPrivateZapEventJson = """
        {
          "id": "9bf0e1d812e10faa5fd3d2560637f8e99fcbfff478583d2c835754d791acfda5",
          "pubkey": "385c3a6ec0b9d57a4330dbd6284989be5bd00e41c535f9ca39b6ae7c521b81cd",
          "created_at": 1708467377,
          "kind": 9733,
          "tags": [
            [
              "p",
              "aa4fc8665f5696e33db7e1a572e3b0f5b3d615837b0f362dcb1c8068b098c7b4"
            ]
          ],
          "content": "Private Zap message!",
          "sig": "48cc725483f41207e83c48f3d01a4e5d341bedcf556e158c19b9faac42bca30e92113049268522cd5052ab3c69850c5df7e2b4fb40c72ce770996edee5a57c8d"
        }
    """.trimIndent()

    @Test
    fun `decodes the real DIP-03 private zap request into a forwardable nip04 payload`() {
        val result = PrivateZap.decodeAnonTag(privateZapRequestEventJson)

        val forward = assertIs<ZapDecodeResult.Forward>(result)
        assertEquals("79d4773fded68a3ea9a30f13e651ff83e150957dacb0c2f6038883d837e1b17b", forward.counterpartyPubkeyHex)
        assertEquals(privateZapAnonTagValue, forward.anonTagValue)

        val (ciphertextB64, ivPart) = forward.nip04Payload.split("?iv=")
        val ciphertext = Base64.getDecoder().decode(ciphertextB64)
        val iv = Base64.getDecoder().decode(ivPart)
        assertEquals(16, iv.size, "AES-CBC IV must be 16 bytes")
        assertEquals(0, ciphertext.size % 16, "AES-CBC ciphertext must be a whole number of blocks")
    }

    @Test
    fun `a kind-9734 request with no anon tag has nothing to decrypt`() {
        val publicZap = """{"kind":9734,"pubkey":"${"a".repeat(64)}","tags":[["p","${"b".repeat(64)}"]]}"""
        assertIs<ZapDecodeResult.NoAnonTag>(PrivateZap.decodeAnonTag(publicZap))
    }

    @Test
    fun `a kind-9734 request with no tags array at all has nothing to decrypt`() {
        val noTags = """{"kind":9734,"pubkey":"${"a".repeat(64)}"}"""
        assertIs<ZapDecodeResult.NoAnonTag>(PrivateZap.decodeAnonTag(noTags))
    }

    @Test
    fun `an event that is not kind 9734 is not a zap request, even with an anon-looking tag`() {
        val wrongKind = """{"kind":1,"pubkey":"${"a".repeat(64)}","tags":[["anon","pzap1x_iv1x"]]}"""
        assertIs<ZapDecodeResult.NotAZapRequest>(PrivateZap.decodeAnonTag(wrongKind))
    }

    @Test
    fun `an event with no kind field at all is not a zap request`() {
        val noKind = """{"pubkey":"${"a".repeat(64)}"}"""
        assertIs<ZapDecodeResult.NotAZapRequest>(PrivateZap.decodeAnonTag(noKind))
    }

    @Test
    fun `invalid json is malformed, not treated as a public zap`() {
        assertIs<ZapDecodeResult.Malformed>(PrivateZap.decodeAnonTag("not json at all"))
    }

    @Test
    fun `a kind-9734 event missing pubkey is malformed`() {
        val noPubkey = """{"kind":9734,"tags":[["anon","pzap1x_iv1x"]]}"""
        assertIs<ZapDecodeResult.Malformed>(PrivateZap.decodeAnonTag(noPubkey))
    }

    @Test
    fun `an anon tag without an underscore separator is malformed`() {
        val noUnderscore = """{"kind":9734,"pubkey":"${"a".repeat(64)}","tags":[["anon","pzap1nopeiv1nope"]]}"""
        assertIs<ZapDecodeResult.MalformedAnon>(PrivateZap.decodeAnonTag(noUnderscore))
    }

    @Test
    fun `an anon tag with a corrupted bech32 checksum is malformed`() {
        val corruptedAnon = privateZapRequestEventJson.replace("v6t79qk", "v6t79qp")
        assertIs<ZapDecodeResult.MalformedAnon>(PrivateZap.decodeAnonTag(corruptedAnon))
    }

    @Test
    fun `recognises the real DIP-03 decrypted event as a valid kind-9733 private zap`() {
        assertTrue(PrivateZap.isValidPrivateZapEvent(decryptedPrivateZapEventJson))
    }

    @Test
    fun `does not accept a different kind as a valid private zap`() {
        val kind1 = decryptedPrivateZapEventJson.replace("\"kind\": 9733", "\"kind\": 1")
        assertFalse(PrivateZap.isValidPrivateZapEvent(kind1))
    }

    @Test
    fun `does not accept invalid json as a valid private zap`() {
        assertFalse(PrivateZap.isValidPrivateZapEvent("not json"))
    }
}

package dev.forgesworn.cambium.unlock

import dev.forgesworn.cambium.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared Sapwood/Cambium vector (enrol-invite spec v1, `test/fixtures/enrol-invite-v1.json`
 * in Sapwood): Sapwood generates it with nostr-tools' internal `nip44.encrypt(..., nonce)`;
 * Cambium must decrypt it and, given the same throwaway secret and nonce, reproduce the exact
 * same ciphertext.
 */
class EnrolInviteVectorTest {

    private fun field(fixture: JsonObject, name: String) = fixture[name]!!.jsonPrimitive.content

    @Test
    fun `the vector's content decrypts to the exact plaintext`() {
        val fixture = load()
        val invSecret = field(fixture, "invSecretHex").hexToBytesOrNull()!!
        val throwawayPubkeyHex = field(fixture, "throwawayPubkeyHex")
        val plaintext = field(fixture, "plaintext")
        assertEquals(plaintext, Nip44.decrypt(invSecret, throwawayPubkeyHex, field(fixture, "eventContent")))
    }

    @Test
    fun `given the same throwaway secret and nonce, Cambium reproduces the exact ciphertext`() {
        val fixture = load()
        val invite = InviteUri.parse(field(fixture, "inviteUri"), nowSecs = field(fixture, "expiresAt").toLong() - 600)!!
        val throwawaySecret = field(fixture, "throwawaySecretHex").hexToBytesOrNull()!!
        val nonce = field(fixture, "nonceHex").hexToBytesOrNull()!!
        val reply = InviteReplyBuilder.build(invite, field(fixture, "plaintext"), throwawaySecret, nonce)!!
        assertEquals(field(fixture, "throwawayPubkeyHex"), reply.throwawayPubkeyHex)
        assertEquals(field(fixture, "eventContent"), reply.content)
    }

    @Test
    fun `the vector's own invite URI parses to its own fields`() {
        val fixture = load()
        val invite = InviteUri.parse(field(fixture, "inviteUri"), nowSecs = field(fixture, "expiresAt").toLong() - 600)
        assertEquals(field(fixture, "invPubkeyHex"), invite?.inviterPubkeyHex)
        assertEquals(field(fixture, "rendezvousHex"), invite?.rendezvous)
        assertEquals(field(fixture, "expiresAt").toLong(), invite?.expiresAtSecs)
        assertEquals(listOf("wss://relay.example"), invite?.relays)
    }

    @Test
    fun `the vector's own throwaway secret really does derive its published pubkey`() {
        val fixture = load()
        val throwawaySecret = field(fixture, "throwawaySecretHex").hexToBytesOrNull()!!
        assertEquals(field(fixture, "throwawayPubkeyHex"), Secp256k1.publicKeyXOnly(throwawaySecret).toHex())
    }

    private fun load(): JsonObject {
        val text = javaClass.classLoader!!.getResource("enrol-invite-v1.json")!!.readText()
        return Json.parseToJsonElement(text) as JsonObject
    }
}

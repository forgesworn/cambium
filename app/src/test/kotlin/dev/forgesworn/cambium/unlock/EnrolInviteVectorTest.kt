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
 * same ciphertext. This uses the test-only reference [Nip44]/[Secp256k1] (never production code:
 * see their own class docs) purely so the wire format can be checked on the host JVM; production
 * (`signer/UnlockRelay.kt`'s `UnlockNostr.inviteReplyEvent`) does the real encryption and signing
 * through rust-nostr, with a random nonce, and cannot itself run in a plain unit test.
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
        val throwawaySecret = field(fixture, "throwawaySecretHex").hexToBytesOrNull()!!
        val nonce = field(fixture, "nonceHex").hexToBytesOrNull()!!
        val invPubkeyHex = field(fixture, "invPubkeyHex")
        val content = Nip44.encrypt(throwawaySecret, invPubkeyHex, field(fixture, "plaintext"), nonce)
        assertEquals(field(fixture, "eventContent"), content)
    }

    @Test
    fun `the reply's tags match the vector's own rendezvous and expiry, nothing else`() {
        val fixture = load()
        val invite = InviteUri.parse(field(fixture, "inviteUri"), nowSecs = field(fixture, "expiresAt").toLong() - 600)!!
        assertEquals(
            listOf("h" to field(fixture, "rendezvousHex"), "expiration" to field(fixture, "expiresAt")),
            InviteReplyBuilder.tags(invite),
        )
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

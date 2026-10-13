package dev.forgesworn.cambium.signer

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.*

/** Native crypto boundary without remote traffic or persisted user keys. */
@RunWith(AndroidJUnit4::class)
class Nip46EnvelopeTest {
    @Test fun signedEncryptedSwitchRequestAndAuthenticatedReply() {
        val client = Keys.generate()
        val signer = Keys.generate()
        val stranger = Keys.generate()
        val request = Nip46Envelope.request(client, signer.publicKey(), "request", "switch_relays")
        assertTrue(request.verify())
        assertEquals(client.publicKey().toHex(), request.author().toHex())
        val plain = nip44Decrypt(signer.secretKey(), client.publicKey(), request.content())
        val json = Json.parseToJsonElement(plain).jsonObject
        assertEquals("switch_relays", json["method"]?.jsonPrimitive?.content)
        assertEquals(JsonArray(emptyList()), json["params"])
        val responseJson = """{"id":"request","result":["wss://new.example"]}"""
        fun reply(author: Keys = signer, recipient: PublicKey = client.publicKey(), content: String = responseJson): Event =
            EventBuilder(Kind(24133u), nip44Encrypt(author.secretKey(), client.publicKey(), content, Nip44Version.V2))
                .tags(listOf(Tag.publicKey(recipient))).signWithKeys(author)
        fun open(event: Event) = Nip46Envelope.openResponse(event, client, signer.publicKey().toHex(), "request")
        assertEquals(RelaySwitchResult.Updated(listOf("wss://new.example")), RelaySwitch.parse(open(reply())!!))
        assertNull(open(reply(author = stranger)))
        assertNull(open(reply(recipient = stranger.publicKey())))
        assertNull(open(reply(content = """{"id":"stale","result":["wss://new.example"]}""")))
        val original = Json.parseToJsonElement(reply().asJson()).jsonObject
        val tampered = JsonObject(original + ("sig" to JsonPrimitive("0".repeat(128))))
        assertNull(open(Event.fromJson(tampered.toString())))
    }
}

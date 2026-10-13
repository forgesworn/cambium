package dev.forgesworn.cambium.signer

import kotlinx.serialization.json.*
import kotlin.test.*

class RelaySwitchTest {
    @Test fun `transport list accepts only bounded secure endpoints`() {
        assertEquals(listOf("wss://relay.example/path"), RelaySwitch.validated(listOf("wss://relay.example/path", "wss://relay.example/path")))
        listOf(emptyList(), List(9) { "wss://r$it.example" }, listOf("ws://localhost"),
            listOf("https://relay.example"), listOf("wss://user:password@relay.example"),
            listOf("wss://relay.example/#fragment"), listOf("wss://relay.example:99999"),
            listOf("wss://relay.example:0"), listOf("wss://"), listOf("wss://relay.example/ bad"),
            listOf("wss://relay.example/" + "a".repeat(2048))).forEach { assertNull(RelaySwitch.validated(it), it.toString()) }
    }

    @Test fun `response requires exact author recipient and random request id`() {
        val payload = """{"id":"ours","result":["wss://new.example"]}"""
        fun read(author: String = "signer", recipient: String = "client", id: String = "ours") =
            RelaySwitch.correlatedResponse(payload, id, author, "signer", listOf(recipient), "client")
        assertNotNull(read())
        assertNull(read(author = "other"))
        assertNull(read(recipient = "other"))
        assertNull(read(id = "other"))
        assertNull(RelaySwitch.correlatedResponse("x".repeat(20_000), "ours", "signer", "signer", listOf("client"), "client"))
    }

    @Test fun `native and stringified arrays null unsupported and invalid replies remain distinct`() {
        fun parse(value: String) = RelaySwitch.parse(Json.parseToJsonElement(value).jsonObject)
        assertEquals(RelaySwitchResult.Updated(listOf("wss://new.example")), parse("""{"result":["wss://new.example"]}"""))
        assertEquals(RelaySwitchResult.Updated(listOf("wss://new.example")), parse("""{"result":"[\"wss://new.example\"]"}"""))
        assertEquals(RelaySwitchResult.Unchanged, parse("""{"result":null}"""))
        assertEquals(RelaySwitchResult.Unchanged, parse("""{"result":"null"}"""))
        assertEquals(RelaySwitchResult.Unsupported, parse("""{"error":"unknown method switch_relays"}"""))
        assertEquals(RelaySwitchResult.Unavailable, parse("""{"error":"denied"}"""))
        listOf("{}", """{"result":[]}""", """{"result":[123]}""", """{"result":["ws://bad.example"]}""",
            """{"result":"garbage"}""").forEach { assertEquals(RelaySwitchResult.Invalid, parse(it)) }
    }

    @Test fun `wire request contains empty params and exact command`() {
        val request = Json.parseToJsonElement(RelaySwitch.request("request-id", "switch_relays")).jsonObject
        assertEquals(JsonPrimitive("request-id"), request["id"])
        assertEquals(JsonPrimitive("switch_relays"), request["method"])
        assertEquals(JsonArray(emptyList()), request["params"])
    }
}

package dev.forgesworn.cambium.nip55

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestPresentationTest {
    private val publicKey = "ab".repeat(32)
    private fun event(kind: Int, content: String = "Hello", tags: JsonArray = JsonArray(emptyList())) =
        Nip55Request.SignEvent(null, null, JsonObject(mapOf(
            "kind" to JsonPrimitive(kind), "content" to JsonPrimitive(content), "tags" to tags,
            "pubkey" to JsonPrimitive(publicKey), "created_at" to JsonPrimitive(1234),
        )).toString())

    @Test
    fun `common kinds describe the intended action`() {
        val summaries = mapOf(
            0 to "Update your public profile", 1 to "Publish a text note", 3 to "Update your follow list",
            5 to "Request deletion of Nostr events", 6 to "Repost a Nostr event", 7 to "React to a Nostr event",
            22242 to "Authenticate with a Nostr relay", 27235 to "Authorise an HTTP request",
            30078 to "Sign app-specific data", 31234 to "Save an encrypted draft", 9734 to "Sign a zap request",
        )
        for ((kind, summary) in summaries) assertEquals(summary, RequestPresenter.describe(event(kind)).summary)
        assertEquals("Sign a Nostr event (kind 12345)", RequestPresenter.describe(event(12345)).summary)
    }

    @Test
    fun `public event preview includes author time content and tags`() {
        val tags = JsonArray(listOf(JsonArray(listOf(JsonPrimitive("p"), JsonPrimitive(publicKey)))))
        val details = RequestPresenter.describe(event(1, "A public note", tags)).details!!
        assertTrue(details.contains("Author: $publicKey"))
        assertTrue(details.contains("Created at (Unix seconds): 1234"))
        assertTrue(details.contains("A public note"))
        assertTrue(details.contains("p | $publicKey"))
    }

    @Test
    fun `compatibility defaults in omitted author timestamp and tags are clear`() {
        val details = RequestPresenter.describe(Nip55Request.SignEvent(null, null, """{"kind":1,"content":"hello"}""")).details!!
        assertTrue(details.contains("selected signer identity"))
        assertTrue(details.contains("set when signing"))
        assertTrue(details.contains("Tags: 0"))
    }

    @Test
    fun `encrypted private opaque and giftwrapped content is never presented`() {
        for (kind in listOf(4, 13, 14, 1059, 30078, 31234)) {
            val details = RequestPresenter.describe(event(kind, "sensitive-ciphertext")).details!!
            assertFalse(details.contains("sensitive-ciphertext"))
            assertTrue(details.contains("Hidden:"))
        }
        val tags = JsonArray(listOf(JsonArray(listOf(JsonPrimitive("anon"), JsonPrimitive("secret-anon-ciphertext")))))
        assertFalse(RequestPresenter.describe(event(9734, tags = tags)).details!!.contains("secret-anon-ciphertext"))
        val marked = JsonArray(listOf(JsonArray(listOf(JsonPrimitive("encrypted")))))
        assertFalse(RequestPresenter.describe(event(12345, "hidden-message", marked)).details!!.contains("hidden-message"))
    }

    @Test
    fun `malformed requests never interpolate untrusted raw JSON`() {
        for (raw in listOf(
            "malformed secret", "[]", """{"kind":1,"content":false}""",
            """{"kind":"1","content":"secret"}""", """{"kind":-1,"content":"secret"}""",
            """{"kind":1,"content":"secret","pubkey":"secret"}""",
            """{"kind":1,"content":"secret","created_at":"secret"}""",
            """{"kind":1,"content":"secret","tags":[[1]]}""",
        )) {
            val presentation = RequestPresenter.describe(Nip55Request.SignEvent(null, null, raw))
            assertEquals("Invalid Nostr event", presentation.summary)
            assertFalse(presentation.details!!.contains("secret"))
        }
    }

    @Test
    fun `preview is bounded and strips control and directional formatting`() {
        val tags = JsonArray(List(100) { index -> JsonArray(listOf(JsonPrimitive("tag-$index"), JsonPrimitive("x".repeat(200)))) })
        val details = RequestPresenter.describe(event(1, "A\u0000B\u202EC" + "x".repeat(3000), tags)).details!!
        assertTrue(details.length <= RequestPresenter.MAX_DETAILS_LENGTH)
        assertFalse(details.contains('\u0000'))
        assertFalse(details.contains('\u202E'))
        assertTrue(details.contains("A B C"))
        assertTrue(details.contains("further tags omitted"))
        assertFalse(details.contains("tag-99"))
        val huge = RequestPresenter.describe(event(1, "x".repeat(300000)))
        assertTrue(huge.summary.contains("too large to preview"))
        assertTrue(huge.details!!.length < 100)
    }

    @Test
    fun `many large tag values still fit the global details bound`() {
        val tags = JsonArray(List(40) {
            JsonArray(List(20) { JsonPrimitive("x".repeat(1000)) })
        })
        val presentation = RequestPresenter.describe(event(1, "hello", tags))
        // Build a valid preview-sized request: the input limit itself also blocks oversized data.
        val boundedTags = JsonArray(tags.take(20).map { tag -> JsonArray((tag as JsonArray).take(8).map {
            JsonPrimitive((it as JsonPrimitive).content.take(200))
        }) })
        val details = RequestPresenter.describe(event(1, "hello", boundedTags)).details!!
        assertTrue(details.length <= RequestPresenter.MAX_DETAILS_LENGTH)
        assertTrue(details.contains("…"))
        assertTrue(presentation.details!!.length <= RequestPresenter.MAX_DETAILS_LENGTH)
    }

    @Test
    fun `crypto previews show only a public counterparty and no message contents`() {
        val requests = listOf(
            Nip55Request.Nip04Encrypt(null, null, publicKey, "private-plaintext"),
            Nip55Request.Nip04Decrypt(null, null, publicKey, "private-ciphertext"),
            Nip55Request.Nip44Encrypt(null, null, publicKey, "private-plaintext"),
            Nip55Request.Nip44Decrypt(null, null, publicKey, "private-ciphertext"),
        )
        for (request in requests) {
            val presentation = RequestPresenter.describe(request)
            assertTrue(presentation.details!!.contains(publicKey))
            assertFalse(presentation.toString().contains("private-plaintext"))
            assertFalse(presentation.toString().contains("private-ciphertext"))
        }
        val invalid = RequestPresenter.describe(Nip55Request.Nip44Decrypt(null, null, "private-secret", "private-ciphertext"))
        assertTrue(invalid.details!!.contains("public key is invalid"))
        assertFalse(invalid.toString().contains("private-secret"))
        assertFalse(RequestPresenter.describe(Nip55Request.DecryptZapEvent(null, null, "private-ciphertext")).toString().contains("private-ciphertext"))
    }
}

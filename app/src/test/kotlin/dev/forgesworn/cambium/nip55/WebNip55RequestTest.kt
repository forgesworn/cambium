package dev.forgesworn.cambium.nip55

import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WebNip55RequestTest {
    private val emptyNative = RawSignerIntent(null, null, null, null, null)

    @Test
    fun `parses Regress NIP-98 login request as one-shot web transport`() {
        val unsignedEvent = """{"kind":27235,"created_at":1786615200,"tags":[["u","https://regress.atobitcoin.io/api/auth"],["method","POST"]],"content":""}"""
        val callback = "https://regress.atobitcoin.io/?signedEvent="
        val uri = "nostrsigner:${encode(unsignedEvent)}" +
            "?type=sign_event" +
            "&callbackUrl=${encode(callback)}" +
            "&returnType=event" +
            "&compressionType=none" +
            "&appName=Regress"

        val incoming = IncomingNip55RequestParser.parse(uri, emptyNative)

        val request = assertIs<Nip55Request.SignEvent>(incoming.parsed)
        assertEquals(unsignedEvent, request.eventJson)
        assertEquals(27235, extractEventKind(request.eventJson))
        val web = assertIs<Nip55Transport.Web>(incoming.transport)
        assertEquals(callback, web.callbackUrl)
        assertEquals("https://regress.atobitcoin.io", web.callbackOrigin)
        assertEquals("regress.atobitcoin.io", web.displayHost)
        assertEquals("Regress", web.appName)
        assertEquals(ReturnType.EVENT, web.returnType)
        assertEquals(CompressionType.NONE, web.compressionType)
        assertEquals(false, web.canUsePackagePermission)
        assertNull(incoming.webError)
    }

    @Test
    fun `native extras retain precedence and native transport`() {
        val native = RawSignerIntent(
            payload = "payload?type=not_a_web_query",
            type = Nip55Request.TYPE_NIP44_ENCRYPT,
            id = "native-1",
            currentUser = "abc",
            pubkey = "deadbeef",
        )

        val incoming = IncomingNip55RequestParser.parse(
            "nostrsigner:payload?type=sign_event&callbackUrl=${encode("https://evil.example/?r=")}",
            native,
        )

        assertIs<Nip55Transport.NativeApp>(incoming.transport)
        assertEquals(true, incoming.transport.canUsePackagePermission)
        val request = assertIs<Nip55Request.Nip44Encrypt>(incoming.parsed)
        assertEquals("payload?type=not_a_web_query", request.plaintext)
        assertEquals("native-1", request.id)
    }

    @Test
    fun `web crypto request decodes payload pubkey identity and request id`() {
        val uri = "nostrsigner:${encode("hello + café")}" +
            "?type=nip44_encrypt&pubkey=deadbeef&current_user=cafe&id=req%2B7"

        val incoming = IncomingNip55RequestParser.parse(uri, emptyNative)

        val request = assertIs<Nip55Request.Nip44Encrypt>(incoming.parsed)
        assertEquals("hello + café", request.plaintext)
        assertEquals("deadbeef", request.pubkeyHex)
        assertEquals("cafe", request.currentUser)
        assertEquals("req+7", request.id)
        val web = assertIs<Nip55Transport.Web>(incoming.transport)
        assertNull(web.callbackUrl)
    }

    @Test
    fun `malformed encoding and unsupported result options fail closed`() {
        assertEquals(
            WebRequestError.MALFORMED_URI,
            IncomingNip55RequestParser.parse("nostrsigner:%ZZ?type=sign_event", emptyNative).webError,
        )
        assertEquals(
            WebRequestError.INVALID_RETURN_TYPE,
            IncomingNip55RequestParser.parse(
                "nostrsigner:%7B%7D?type=sign_event&returnType=thing",
                emptyNative,
            ).webError,
        )
        assertEquals(
            WebRequestError.INVALID_COMPRESSION_TYPE,
            IncomingNip55RequestParser.parse(
                "nostrsigner:%7B%7D?type=sign_event&compressionType=base64",
                emptyNative,
            ).webError,
        )
    }

    @Test
    fun `only secure website and local development callbacks are accepted`() {
        val accepted = listOf(
            "https://example.com/callback?result=",
            "https://example.com:8443/callback?result=",
            "http://localhost:8080/callback?result=",
            "http://127.0.0.1/callback?result=",
            "http://[::1]:8080/callback?result=",
        )
        accepted.forEach { callback ->
            val incoming = parseGetPublicKey(callback)
            assertNull(incoming.webError, callback)
            assertEquals(callback, assertIs<Nip55Transport.Web>(incoming.transport).callbackUrl)
        }

        val rejected = listOf(
            "http://example.com/callback?result=",
            "nostr://example.com/callback?result=",
            "https://user:password@example.com/callback?result=",
            "https://example.com/callback?result=#fragment",
            "https:///missing-host",
        )
        rejected.forEach { callback ->
            assertEquals(WebRequestError.INVALID_CALLBACK_URL, parseGetPublicKey(callback).webError, callback)
        }
    }

    @Test
    fun `signature return opens callback with an RFC3986 encoded signature`() {
        val event = """{"id":"1","kind":27235,"content":"","sig":"deadbeef+00/="}"""
        val transport = web(
            callbackUrl = "https://regress.atobitcoin.io/?signature=",
            returnType = ReturnType.SIGNATURE,
        )
        val request = Nip55Request.SignEvent(null, null, "{}")

        val result = assertIs<WebResult.Success>(WebNip55ResultBuilder.build(transport, request, event))
        val action = assertIs<WebResultAction.OpenCallback>(result.action)

        assertEquals("https://regress.atobitcoin.io/?signature=deadbeef%2B00%2F%3D", action.url)
    }

    @Test
    fun `event return opens callback with the complete signed event`() {
        val event = """{"kind":1,"content":"hello world","sig":"deadbeef"}"""
        val transport = web(
            callbackUrl = "https://example.com/?event=",
            returnType = ReturnType.EVENT,
        )
        val request = Nip55Request.SignEvent(null, null, "{}")

        val result = assertIs<WebResult.Success>(WebNip55ResultBuilder.build(transport, request, event))
        val action = assertIs<WebResultAction.OpenCallback>(result.action)
        val encodedResult = action.url.substringAfter("?event=")

        assertEquals(event, URLDecoder.decode(encodedResult, StandardCharsets.UTF_8.name()))
        assertEquals(false, encodedResult.contains('+'))
    }

    @Test
    fun `gzip event return uses Signer1 envelope and round trips`() {
        val event = """{"kind":1,"content":"compress me","sig":"deadbeef"}"""
        val transport = web(
            callbackUrl = null,
            returnType = ReturnType.EVENT,
            compressionType = CompressionType.GZIP,
        )
        val request = Nip55Request.SignEvent(null, null, "{}")

        val result = assertIs<WebResult.Success>(WebNip55ResultBuilder.build(transport, request, event))
        val action = assertIs<WebResultAction.CopyToClipboard>(result.action)
        val envelope = action.text
        assertEquals("Signer1", envelope.take(7))
        val compressed = Base64.getDecoder().decode(envelope.drop(7))
        val restored = GZIPInputStream(ByteArrayInputStream(compressed)).reader().readText()
        assertEquals(event, restored)
    }

    @Test
    fun `missing signature is rejected instead of returning an unsigned event`() {
        val request = Nip55Request.SignEvent(null, null, "{}")
        val result = WebNip55ResultBuilder.build(web(returnType = ReturnType.SIGNATURE), request, """{"kind":1}""")

        assertEquals(WebResultError.MISSING_EVENT_SIGNATURE, assertIs<WebResult.Failure>(result).error)
    }

    @Test
    fun `non-event results go to clipboard unchanged when callback is absent`() {
        val request = Nip55Request.GetPublicKey(null, null)
        val result = assertIs<WebResult.Success>(
            WebNip55ResultBuilder.build(web(callbackUrl = null), request, "abcdef"),
        )

        assertEquals("abcdef", assertIs<WebResultAction.CopyToClipboard>(result.action).text)
    }

    private fun parseGetPublicKey(callback: String): IncomingNip55Request =
        IncomingNip55RequestParser.parse(
            "nostrsigner:?type=get_public_key&callbackUrl=${encode(callback)}",
            emptyNative,
        )

    private fun web(
        callbackUrl: String? = "https://example.com/?result=",
        returnType: ReturnType = ReturnType.SIGNATURE,
        compressionType: CompressionType = CompressionType.NONE,
    ) = Nip55Transport.Web(
        callbackUrl = callbackUrl,
        callbackOrigin = callbackUrl?.let { "https://example.com" },
        displayHost = callbackUrl?.let { "example.com" },
        appName = null,
        returnType = returnType,
        compressionType = compressionType,
    )

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

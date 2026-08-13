package dev.forgesworn.cambium.nip55

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.GZIPOutputStream

/** How the request arrived and, for a browser request, where its result must go. */
sealed interface Nip55Transport {
    data object NativeApp : Nip55Transport

    /**
     * NIP-55's browser transport. Browser requests are deliberately never rememberable: an
     * Android package name identifies the browser, not the website currently using it.
     */
    data class Web(
        val callbackUrl: String?,
        val callbackOrigin: String?,
        val displayHost: String?,
        val appName: String?,
        val returnType: ReturnType,
        val compressionType: CompressionType,
    ) : Nip55Transport
}

/** Only native app-to-app calls have a package identity that can safely be remembered. */
val Nip55Transport.canUsePackagePermission: Boolean
    get() = this is Nip55Transport.NativeApp

enum class ReturnType { SIGNATURE, EVENT }

enum class CompressionType { NONE, GZIP }

enum class WebRequestError {
    MALFORMED_URI,
    INVALID_RETURN_TYPE,
    INVALID_COMPRESSION_TYPE,
    INVALID_CALLBACK_URL,
}

data class IncomingNip55Request(
    val raw: RawSignerIntent,
    val parsed: Nip55Request?,
    val transport: Nip55Transport,
    val webError: WebRequestError? = null,
)

/**
 * Parses the web-application form of `nostrsigner:` without depending on Android's [Uri].
 * Keeping this wire boundary on the host JVM makes percent-decoding and callback validation
 * directly testable instead of relying on an emulator-only happy path.
 */
object IncomingNip55RequestParser {
    private const val SCHEME_PREFIX = "nostrsigner:"
    private const val MAX_CALLBACK_LENGTH = 4_096
    private const val MAX_APP_NAME_LENGTH = 80

    fun parse(dataUri: String?, nativeRaw: RawSignerIntent): IncomingNip55Request {
        // Extras are the native Android contract. They take precedence even when a payload
        // happens to contain a question mark or query-looking text.
        if (nativeRaw.type != null || dataUri == null || !dataUri.startsWith(SCHEME_PREFIX, ignoreCase = true)) {
            return native(nativeRaw)
        }

        val schemeSpecific = dataUri.substring(SCHEME_PREFIX.length)
        val separator = schemeSpecific.indexOf('?')
        if (separator < 0) return native(nativeRaw)

        val encodedPayload = schemeSpecific.substring(0, separator)
        val encodedQuery = schemeSpecific.substring(separator + 1)
        val params = try {
            parseQuery(encodedQuery)
        } catch (_: IllegalArgumentException) {
            return invalidWeb(nativeRaw, WebRequestError.MALFORMED_URI)
        }

        // A nostrsigner payload may itself contain '?' for the native form. Presence of a web
        // `type` parameter is what identifies NIP-55's browser transport.
        val type = params["type"] ?: return native(nativeRaw)
        val payload = try {
            decode(encodedPayload)
        } catch (_: IllegalArgumentException) {
            return invalidWeb(nativeRaw.copy(type = type), WebRequestError.MALFORMED_URI)
        }

        val returnType = when (params["returnType"]?.lowercase()) {
            null, "", "signature" -> ReturnType.SIGNATURE
            "event" -> ReturnType.EVENT
            else -> return invalidWeb(nativeRaw.copy(type = type), WebRequestError.INVALID_RETURN_TYPE)
        }
        val compressionType = when (params["compressionType"]?.lowercase()) {
            null, "", "none" -> CompressionType.NONE
            "gzip" -> CompressionType.GZIP
            else -> return invalidWeb(nativeRaw.copy(type = type), WebRequestError.INVALID_COMPRESSION_TYPE)
        }
        val callback = when (val callbackUrl = params["callbackUrl"]?.takeIf(String::isNotBlank)) {
            null -> null
            else -> validateCallback(callbackUrl)
                ?: return invalidWeb(nativeRaw.copy(type = type), WebRequestError.INVALID_CALLBACK_URL)
        }

        val raw = RawSignerIntent(
            payload = payload,
            type = type,
            id = params["id"],
            currentUser = params["current_user"],
            pubkey = params["pubkey"] ?: params["pubKey"],
            permissions = params["permissions"],
        )
        val transport = Nip55Transport.Web(
            callbackUrl = callback?.url,
            callbackOrigin = callback?.origin,
            displayHost = callback?.displayHost,
            appName = params["appName"]
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.take(MAX_APP_NAME_LENGTH),
            returnType = returnType,
            compressionType = compressionType,
        )
        return IncomingNip55Request(raw, Nip55Request.from(raw), transport)
    }

    private fun native(raw: RawSignerIntent) = IncomingNip55Request(
        raw = raw,
        parsed = Nip55Request.from(raw),
        transport = Nip55Transport.NativeApp,
    )

    private fun invalidWeb(raw: RawSignerIntent, error: WebRequestError) = IncomingNip55Request(
        raw = raw,
        parsed = null,
        transport = Nip55Transport.Web(
            callbackUrl = null,
            callbackOrigin = null,
            displayHost = null,
            appName = null,
            returnType = ReturnType.SIGNATURE,
            compressionType = CompressionType.NONE,
        ),
        webError = error,
    )

    private fun parseQuery(query: String): Map<String, String> = buildMap {
        query.split('&').forEach { part ->
            if (part.isEmpty()) return@forEach
            val separator = part.indexOf('=')
            val encodedKey = if (separator >= 0) part.substring(0, separator) else part
            val encodedValue = if (separator >= 0) part.substring(separator + 1) else ""
            val key = decode(encodedKey)
            if (key !in this) put(key, decode(encodedValue))
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private data class Callback(
        val url: String,
        val origin: String,
        val displayHost: String,
    )

    private fun validateCallback(value: String): Callback? {
        if (value.length > MAX_CALLBACK_LENGTH) return null
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
        if (uri.isOpaque || uri.rawUserInfo != null || uri.rawFragment != null) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val secure = scheme == "https"
        val localDevelopment = scheme == "http" && host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        if (!secure && !localDevelopment) return null

        val hostForOrigin = if (':' in host && !host.startsWith('[')) "[$host]" else host
        val portSuffix = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
        return Callback(
            url = value,
            origin = "$scheme://$hostForOrigin$portSuffix",
            displayHost = "$hostForOrigin$portSuffix",
        )
    }
}

sealed interface WebResultAction {
    data class OpenCallback(val url: String) : WebResultAction
    data class CopyToClipboard(val text: String) : WebResultAction
}

enum class WebResultError { MISSING_EVENT_SIGNATURE }

sealed interface WebResult {
    data class Success(val action: WebResultAction) : WebResult
    data class Failure(val error: WebResultError) : WebResult
}

/** Builds exactly the result value NIP-55 says a web client receives. */
object WebNip55ResultBuilder {
    fun build(transport: Nip55Transport.Web, request: Nip55Request, result: String): WebResult {
        val value = when {
            request is Nip55Request.SignEvent && transport.compressionType == CompressionType.GZIP -> {
                "Signer1" + Base64.getEncoder().encodeToString(gzip(result.toByteArray(StandardCharsets.UTF_8)))
            }
            request is Nip55Request.SignEvent && transport.returnType == ReturnType.EVENT -> result
            request is Nip55Request.SignEvent -> extractEventSignatureHex(result)
                ?: return WebResult.Failure(WebResultError.MISSING_EVENT_SIGNATURE)
            else -> result
        }

        val callback = transport.callbackUrl
        val action = if (callback == null) {
            WebResultAction.CopyToClipboard(value)
        } else {
            WebResultAction.OpenCallback(callback + percentEncode(value))
        }
        return WebResult.Success(action)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    /** RFC 3986 component encoding. Java's form encoder would incorrectly turn spaces into '+'. */
    private fun percentEncode(value: String): String = buildString {
        value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val value = byte.toInt() and 0xff
            val unreserved = value in 'a'.code..'z'.code ||
                value in 'A'.code..'Z'.code ||
                value in '0'.code..'9'.code ||
                value == '-'.code || value == '.'.code || value == '_'.code || value == '~'.code
            if (unreserved) append(value.toChar()) else append("%${value.toString(16).uppercase().padStart(2, '0')}")
        }
    }
}

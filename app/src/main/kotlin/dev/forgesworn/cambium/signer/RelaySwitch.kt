package dev.forgesworn.cambium.signer

import java.net.URI
import kotlinx.serialization.json.*

sealed interface RelaySwitchResult {
    data class Updated(val relays: List<String>) : RelaySwitchResult
    data object Unchanged : RelaySwitchResult
    data object Unsupported : RelaySwitchResult
    data object Unavailable : RelaySwitchResult
    data object Invalid : RelaySwitchResult
}

/** Bounded NIP-46 transport updates; these are not the user's NIP-65 relay lists. */
object RelaySwitch {
    const val MAX_RELAYS = 8
    const val MAX_RESPONSE_CHARS = 16_384

    fun validated(relays: List<String>): List<String>? {
        if (relays.isEmpty() || relays.size > MAX_RELAYS) return null
        if (relays.any { raw ->
                raw.length > 2048 || raw.any { it.isWhitespace() || it.isISOControl() } ||
                    runCatching {
                        val uri = URI(raw)
                        uri.scheme != "wss" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                            uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0
                    }.getOrDefault(true)
            }) return null
        return relays.distinct()
    }

    fun request(id: String, method: String): String = buildJsonObject {
        put("id", id)
        put("method", method)
        put("params", JsonArray(emptyList()))
    }.toString()

    /** Called only after SDK signature verification and NIP-44 authentication. Unrelated replies
     * never finish a pending request, including another RPC using the same client identity. */
    fun correlatedResponse(
        plaintext: String,
        requestId: String,
        author: String,
        expectedSigner: String,
        recipients: List<String>,
        expectedClient: String,
    ): JsonObject? {
        if (author != expectedSigner || expectedClient !in recipients || plaintext.length > MAX_RESPONSE_CHARS) return null
        return runCatching {
            val response = Json.parseToJsonElement(plaintext).jsonObject
            response.takeIf { (it["id"] as? JsonPrimitive)?.takeIf { id -> id.isString }?.content == requestId }
        }.getOrNull()
    }

    fun parse(response: JsonObject): RelaySwitchResult {
        val error = response["error"]
        if (error != null && error != JsonNull) {
            val text = (error as? JsonPrimitive)?.content.orEmpty().lowercase()
            return if (listOf("unsupported", "unknown method", "not implemented", "method not found").any(text::contains))
                RelaySwitchResult.Unsupported else RelaySwitchResult.Unavailable
        }
        var result = response["result"] ?: return RelaySwitchResult.Invalid
        // NIP-46's response envelope also permits a JSON-stringified result.
        if (result is JsonPrimitive && result.isString) {
            val encoded = result.content
            result = runCatching { Json.parseToJsonElement(encoded) }.getOrNull() ?: return RelaySwitchResult.Invalid
        }
        if (result == JsonNull) return RelaySwitchResult.Unchanged
        val values = result as? JsonArray ?: return RelaySwitchResult.Invalid
        val relays = values.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: return RelaySwitchResult.Invalid }
        return validated(relays)?.let(RelaySwitchResult::Updated) ?: RelaySwitchResult.Invalid
    }
}

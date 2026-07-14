package dev.forgesworn.cambium.nip55

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Small helpers for pulling display/compat fields out of a signed or unsigned event JSON string.
 * kotlinx.serialization rather than `org.json`, for the same reason as `nip57/PrivateZap.kt`:
 * `org.json` exists only as a non-functional stub on the host JVM, so these would be untestable
 * (and their tests meaningless) against it.
 */

internal fun extractEventKind(eventJson: String): Int? = runCatching {
    Json.parseToJsonElement(eventJson).jsonObject["kind"]
        ?.takeIf { it !is JsonNull }
        ?.jsonPrimitive?.int
}.getOrNull()

internal fun extractEventSignatureHex(eventJson: String): String? = runCatching {
    Json.parseToJsonElement(eventJson).jsonObject["sig"]
        ?.takeIf { it !is JsonNull }
        ?.jsonPrimitive?.content
}.getOrNull()

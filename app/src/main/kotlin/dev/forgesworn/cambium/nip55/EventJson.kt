package dev.forgesworn.cambium.nip55

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * Returns [eventJson] with `created_at` defaulted to [nowEpochSeconds] and `tags` to `[]`
 * when either field is absent. NIP-55 clients omit both from `sign_event` payloads and Amber
 * quietly fills the gaps at signing time, so omission is de-facto valid on this wire --
 * Primal's login event arrives without `created_at` and its wallet-operation event without
 * `tags` (both verified live, July 2026) -- but rust-nostr's `UnsignedEvent.fromJson` rejects
 * each omission outright ("missing field ..."). Defaulting them here keeps the de-facto
 * contract without touching the FFI boundary. Anything that does not parse as a JSON object
 * is returned unchanged: rust-nostr's own parser stays the single authority on what is
 * malformed, and so are missing fields with no safe default (pubkey, kind, content).
 */
internal fun normaliseUnsignedEvent(eventJson: String, nowEpochSeconds: Long): String = runCatching {
    val obj = Json.parseToJsonElement(eventJson).jsonObject
    val defaults = buildMap {
        if ("created_at" !in obj) put("created_at", JsonPrimitive(nowEpochSeconds))
        if ("tags" !in obj) put("tags", JsonArray(emptyList()))
    }
    if (defaults.isEmpty()) eventJson else JsonObject(obj + defaults).toString()
}.getOrDefault(eventJson)

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
 * Returns [eventJson] with `pubkey` defaulted to [signerPubkeyHex], `created_at` to
 * [nowEpochSeconds], and `tags` to `[]` when any are absent. NIP-55's own web example sends only
 * `kind` and `content`; Amber fills the signer-owned fields, and Primal also omits `created_at`
 * or `tags` in live requests. rust-nostr's `UnsignedEvent.fromJson` rejects each omission before
 * the request can reach Heartwood, so the compatibility defaults belong immediately before that
 * FFI boundary. [signerPubkeyHex] comes from the successful NIP-46 handshake for this exact
 * per-identity session, not from ambient UI state, so it cannot silently select another pairing.
 * An explicit event pubkey is preserved for rust-nostr/Heartwood to validate as before.
 *
 * Anything that does not parse as a JSON object is returned unchanged: rust-nostr's own parser
 * stays the single authority on what is malformed, as it does for missing `kind` or `content`,
 * which have no safe defaults.
 */
internal fun normaliseUnsignedEvent(
    eventJson: String,
    signerPubkeyHex: String,
    nowEpochSeconds: Long,
): String = runCatching {
    val obj = Json.parseToJsonElement(eventJson).jsonObject
    val defaults = buildMap {
        if ("pubkey" !in obj) put("pubkey", JsonPrimitive(signerPubkeyHex))
        if ("created_at" !in obj) put("created_at", JsonPrimitive(nowEpochSeconds))
        if ("tags" !in obj) put("tags", JsonArray(emptyList()))
    }
    if (defaults.isEmpty()) eventJson else JsonObject(obj + defaults).toString()
}.getOrDefault(eventJson)

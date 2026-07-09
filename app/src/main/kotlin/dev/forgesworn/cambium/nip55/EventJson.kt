package dev.forgesworn.cambium.nip55

import org.json.JSONObject

/** Small helpers for pulling display/compat fields out of a signed or unsigned event JSON string. */

internal fun extractEventKind(eventJson: String): Int? = runCatching {
    JSONObject(eventJson).getInt("kind")
}.getOrNull()

internal fun extractEventSignatureHex(eventJson: String): String? = runCatching {
    JSONObject(eventJson).getString("sig")
}.getOrNull()

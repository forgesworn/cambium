package dev.forgesworn.cambium.nip55

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single entry from `get_public_key`'s optional `permissions` extra (NIP-55: a JSON array of
 * `{ "type": "sign_event", "kind": 22242 }`-shaped objects, `kind` only meaningful for
 * `sign_event`), pre-authorising methods so the content-provider path can answer them silently
 * later. Cambium does not implement pre-authorisation from this list -- see [SignerActivity]'s
 * approval flow -- this is display-only, shown on the first-approval sheet so the user knows what
 * was asked for. Heartwood's own `ClientPolicy` remains the actual authority on what gets signed.
 */
data class RequestedPermission(val type: String, val kind: Int?)

/** Parses [json] (the raw `permissions` extra) into a list of [RequestedPermission]s. Never
 * throws: `null`, blank, or malformed input all produce an empty list. Pure Kotlin, JVM-testable. */
fun parseRequestedPermissions(json: String?): List<RequestedPermission> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
            RequestedPermission(type, obj["kind"]?.jsonPrimitive?.intOrNull)
        }
    }.getOrDefault(emptyList())
}

/** A short, human-readable summary for the approval sheet, e.g. "sign_event (kind 1), nip44_decrypt". */
fun summariseRequestedPermissions(permissions: List<RequestedPermission>): String =
    permissions.joinToString(", ") { permission ->
        if (permission.kind != null) "${permission.type} (kind ${permission.kind})" else permission.type
    }

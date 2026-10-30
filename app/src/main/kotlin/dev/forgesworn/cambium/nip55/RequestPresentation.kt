package dev.forgesworn.cambium.nip55

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Approval-screen memory only. Never store these details in the activity log or a report. */
data class RequestPresentation(val summary: String, val details: String?)

/** Bounded display of untrusted request data; this is a preview, never the signing authority. */
object RequestPresenter {
    const val MAX_DETAILS_LENGTH = 8192
    private const val MAX_EVENT_LENGTH = 262144
    private const val MAX_CONTENT_LENGTH = 1200
    private const val MAX_TAGS = 16
    private const val MAX_TAG_FIELDS = 6
    private const val MAX_TAG_FIELD_LENGTH = 120
    private val PUBLIC_KEY = Regex("[0-9a-fA-F]{64}")
    private val OPAQUE_KINDS = setOf(4, 13, 14, 1059, 30078, 31234)

    fun describe(request: Nip55Request): RequestPresentation = when (request) {
        is Nip55Request.GetPublicKey -> RequestPresentation("Share your public identity", null)
        is Nip55Request.SignEvent -> event(request.eventJson)
        is Nip55Request.Nip04Encrypt -> crypto("Encrypt a message using NIP-04", request.pubkeyHex)
        is Nip55Request.Nip04Decrypt -> crypto("Decrypt a message using NIP-04", request.pubkeyHex)
        is Nip55Request.Nip44Encrypt -> crypto("Encrypt a message using NIP-44", request.pubkeyHex)
        is Nip55Request.Nip44Decrypt -> crypto("Decrypt a message using NIP-44", request.pubkeyHex)
        is Nip55Request.DecryptZapEvent -> RequestPresentation(
            "Decrypt a private zap sent to your identity",
            "Only the recipient path is supported. The encrypted zap contents are not shown in this preview.",
        )
    }

    private fun crypto(summary: String, publicKey: String): RequestPresentation = RequestPresentation(
        summary,
        if (PUBLIC_KEY.matches(publicKey)) "Counterparty public key: ${publicKey.lowercase()}\nMessage contents are not shown in this preview."
        else "The counterparty public key is invalid. Message contents are not shown in this preview.",
    )

    private fun event(raw: String): RequestPresentation {
        if (raw.length > MAX_EVENT_LENGTH) return RequestPresentation(
            "Sign a Nostr event too large to preview", "Event details exceed the preview limit.",
        )
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return invalid()
        val kindValue = obj["kind"] as? JsonPrimitive ?: return invalid()
        val kind = kindValue.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..65535 } ?: return invalid()
        val content = (obj["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return invalid()
        val author = obj["pubkey"]?.let { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString && PUBLIC_KEY.matches(it.content) }?.content
                ?: return invalid()
        }
        val createdAt = obj["created_at"]?.let { value ->
            (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
                ?: return invalid()
        }
        val tags = obj["tags"]?.let { it as? JsonArray ?: return invalid() } ?: JsonArray(emptyList())
        if (tags.any { tag -> tag !is JsonArray || tag.any { it !is JsonPrimitive || !it.isString } }) return invalid()
        val encrypted = kind in OPAQUE_KINDS || tags.any { tag ->
            (tag as JsonArray).firstOrNull()?.let { (it as JsonPrimitive).content == "encrypted" } == true
        }
        val details = buildString {
            appendLine("Kind: $kind")
            appendLine("Author: ${author?.lowercase() ?: "selected signer identity"}")
            appendLine("Created at (Unix seconds): ${createdAt ?: "set when signing"}")
            appendLine("Content:")
            appendLine(if (encrypted) "Hidden: this event carries private, encrypted or app-specific data."
                else sanitise(content, MAX_CONTENT_LENGTH).ifEmpty { "(empty)" })
            appendLine("Tags: ${tags.size}")
            for (tagValue in tags.take(MAX_TAGS)) {
                val tag = tagValue as JsonArray
                val name = (tag.firstOrNull() as? JsonPrimitive)?.content
                if (name == "anon" || name == "encrypted") {
                    appendLine("${sanitise(name, MAX_TAG_FIELD_LENGTH)}: (private data hidden)")
                } else {
                    appendLine(tag.take(MAX_TAG_FIELDS).joinToString(" | ") {
                        sanitise((it as JsonPrimitive).content, MAX_TAG_FIELD_LENGTH)
                    } + if (tag.size > MAX_TAG_FIELDS) " | …" else "")
                }
            }
            if (tags.size > MAX_TAGS) appendLine("… further tags omitted")
            append("This is a shortened preview. Heartwood validates the signing request.")
        }.take(MAX_DETAILS_LENGTH)
        return RequestPresentation(summaryFor(kind), details)
    }

    private fun invalid() = RequestPresentation("Invalid Nostr event", "The event cannot be read as a valid signing request.")

    private fun summaryFor(kind: Int): String = when (kind) {
        0 -> "Update your public profile"
        1 -> "Publish a text note"
        3 -> "Update your follow list"
        4 -> "Sign an encrypted direct message"
        5 -> "Request deletion of Nostr events"
        6, 16 -> "Repost a Nostr event"
        7 -> "React to a Nostr event"
        13 -> "Sign an encrypted seal"
        14 -> "Sign a private chat message"
        1059 -> "Sign a gift-wrapped private event"
        9734 -> "Sign a zap request"
        9735 -> "Sign a zap receipt"
        10002 -> "Update your relay list"
        22242 -> "Authenticate with a Nostr relay"
        27235 -> "Authorise an HTTP request"
        30078 -> "Sign app-specific data"
        31234 -> "Save an encrypted draft"
        // ForgeSworn's own kinds, so a request from V4V or KithMoot reads as the
        // action it is rather than a number. The kind still shows in the details.
        1311 -> "Send a live stage chat message"
        20460 -> "Issue a KithMoot device credential"
        21236 -> "Sign in to a site with your account"
        21350 -> "Revoke a KithMoot device"
        27117 -> "Authorise a V4V release's key deposit or price"
        27118 -> "Release a V4V gated key to a public source"
        27201 -> "Prove ownership of your V4V library"
        27202 -> "Read or settle your V4V sales"
        27541 -> "Grant access to a KithMoot group"
        30003 -> "Update a bookmark set"
        30315 -> "Update your listening status"
        30808 -> "Publish a V4V gated release"
        else -> "Sign a Nostr event (kind $kind)"
    }

    /** Strip invisible control/format characters that could disguise request content or labels. */
    private fun sanitise(value: String, limit: Int): String {
        val bounded = value.take(limit)
        val visible = buildString {
            for (character in bounded) {
                val type = Character.getType(character)
                append(if (Character.isISOControl(character) || type == Character.FORMAT.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()) ' ' else character)
            }
        }
        return visible + if (value.length > limit) "…" else ""
    }
}

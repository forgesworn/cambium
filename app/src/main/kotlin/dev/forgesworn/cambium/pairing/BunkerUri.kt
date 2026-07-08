package dev.forgesworn.cambium.pairing

import java.net.URLDecoder

/**
 * A parsed `bunker://` NIP-46 connection URI, as produced by Sapwood's "Connect an app" flow.
 *
 * Deliberately pure Kotlin (no Android, no rust-nostr): rust-nostr ships native code per ABI
 * and cannot load on a plain host JVM, so any class touching it is unusable in fast JVM unit
 * tests. This parser stays free of that dependency so [BunkerUriTest] can run without an
 * Android instrumentation target.
 */
data class BunkerUri(
    val signerPubkeyHex: String,
    val relays: List<String>,
    val secret: String?,
) {
    /** Reconstructs the canonical URI string, e.g. for round-tripping through rust-nostr's own parser. */
    fun toUriString(): String {
        val params = buildList {
            relays.forEach { add("relay=${encode(it)}") }
            secret?.let { add("secret=${encode(it)}") }
        }
        return "bunker://$signerPubkeyHex" + if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}

sealed interface BunkerUriResult {
    data class Valid(val uri: BunkerUri) : BunkerUriResult
    data class Invalid(val reason: String) : BunkerUriResult
}

object BunkerUriParser {

    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
    private const val SCHEME = "bunker"

    fun parse(raw: String): BunkerUriResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return BunkerUriResult.Invalid("Empty URI")
        }

        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0 || !trimmed.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)) {
            return BunkerUriResult.Invalid("URI must start with bunker://")
        }

        val afterScheme = trimmed.substring(schemeEnd + 3)
        val queryStart = afterScheme.indexOf('?')
        val authority = if (queryStart >= 0) afterScheme.substring(0, queryStart) else afterScheme
        val query = if (queryStart >= 0) afterScheme.substring(queryStart + 1) else ""

        val pubkeyPart = authority.substringBefore('/')
        if (pubkeyPart.isEmpty()) {
            return BunkerUriResult.Invalid("URI is missing the signer's public key")
        }
        if (!HEX_64.matches(pubkeyPart)) {
            return BunkerUriResult.Invalid("Signer public key must be 64 hex characters, got: $pubkeyPart")
        }

        val relays = LinkedHashSet<String>()
        var secret: String? = null

        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq >= 0) pair.substring(0, eq) else pair
                val rawValue = if (eq >= 0) pair.substring(eq + 1) else ""
                val value = decode(rawValue)

                when {
                    key.equals("relay", ignoreCase = true) -> {
                        if (value.startsWith("wss://", ignoreCase = true)) {
                            relays.add(value)
                        }
                        // Non-wss relays (ws://, http:// etc.) are silently dropped: Cambium
                        // only ever dials out over TLS relay connections.
                    }
                    key.equals("secret", ignoreCase = true) -> {
                        if (secret == null && value.isNotEmpty()) {
                            secret = value
                        }
                    }
                }
            }
        }

        if (relays.isEmpty()) {
            return BunkerUriResult.Invalid("URI must include at least one wss:// relay")
        }

        return BunkerUriResult.Valid(
            BunkerUri(
                signerPubkeyHex = pubkeyPart.lowercase(),
                relays = relays.toList(),
                secret = secret,
            )
        )
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)
}

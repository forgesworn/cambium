package dev.forgesworn.cambium.unlock

import java.net.URLDecoder

/**
 * Sapwood's invite (enrol-invite spec v1), reversed from [EnrolmentCode]'s own QR: Sapwood shows
 * this, Cambium scans it.
 *
 * ```
 * heartwood-unlock:invite?v=1&k=<inv pubkey hex64>&r=<ri hex32>&x=<unix>&relay=<wss url>[&relay=...]
 * ```
 *
 * [inviterPubkeyHex] (`inv`) is Sapwood's one-off key, kept only in page memory; [rendezvous] (`ri`)
 * is the one-off `h` tag Cambium's reply is addressed to; [expiresAtSecs] (`x`) is when Sapwood
 * stops listening; [relays] are where it is listening. Parsing is deliberately as strict as
 * [EnrolmentCode.parse]: a repeated single-valued parameter, or a relay that is not `wss://` (or
 * `ws://localhost` for a test bench) refuses the whole code, rather than guessing which value was
 * meant.
 */
data class InviteUri(
    val inviterPubkeyHex: String,
    val rendezvous: String,
    val expiresAtSecs: Long,
    val relays: List<String>,
) {
    companion object {
        const val SCHEME = "heartwood-unlock"
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val HEX32 = Regex("^[0-9a-f]{32}$")
        /** Sapwood generates a fresh invite every 10 minutes; an hour is a generous clock-skew margin. */
        const val MAX_FUTURE_SECS = 3600L

        /**
         * Parses [text] as an invite, or null for anything malformed, expired, or too far in the
         * future. [nowSecs] is injectable so a test does not need to race a wall clock.
         */
        fun parse(text: String, nowSecs: Long = System.currentTimeMillis() / 1000): InviteUri? {
            val prefix = "$SCHEME:invite?"
            if (!text.startsWith(prefix)) return null
            val params = text.removePrefix(prefix).split('&').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) null else pair.substring(0, eq) to runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrNull()
            }
            fun one(name: String) = params.singleOrNull { it.first == name }?.second
            if (one("v") != "1") return null
            val k = one("k")?.takeIf { HEX64.matches(it) } ?: return null
            val r = one("r")?.takeIf { HEX32.matches(it) } ?: return null
            val x = one("x")?.toLongOrNull() ?: return null
            if (x <= nowSecs || x > nowSecs + MAX_FUTURE_SECS) return null
            val relays = params.filter { it.first == "relay" }.map { it.second ?: return null }.distinct()
            if (relays.isEmpty() || relays.any { !isInviteRelayUrl(it) }) return null
            return InviteUri(k, r, x, relays)
        }
    }
}

private fun isInviteRelayUrl(url: String): Boolean =
    (url.startsWith("wss://") || url.startsWith("ws://localhost")) && url.length in 7..256 && url.none { it.isWhitespace() }

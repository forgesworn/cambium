package dev.forgesworn.cambium.unlock

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What Cambium shows (as a QR and as text) when it asks to become an unlock phone:
 *
 * ```
 * heartwood-unlock:enrol?v=1&p=<enrolment pubkey hex>&r=<rendezvous tag hex>&label=<name>&relay=<url>&relay=...
 * ```
 *
 * Everything in it is safe to show: [enrolPubkeyHex] is a one-off key that only opens the hand-off
 * (its secret half never leaves this screen), [rendezvous] is a random tag used once, [label] is
 * what the board lists this phone as (at most [MAX_LABEL_BYTES], the board's own limit), and
 * [relays] are where Cambium waits for the hand-off. Sapwood (or the bench script) enrols [enrolPubkeyHex]
 * on the board with a press, then publishes the board's answer as a [PhoneUnlock.HANDOFF_KIND]
 * event tagged `["h", rendezvous]` on [relays]. Nothing secret passes through Sapwood: the answer
 * is sealed to the enrolment key.
 */
data class EnrolmentCode(
    val enrolPubkeyHex: String,
    val rendezvous: String,
    val label: String,
    val relays: List<String>,
) {
    fun encode(): String = buildString {
        append(SCHEME).append(":enrol?v=1")
        append("&p=").append(enrolPubkeyHex)
        append("&r=").append(rendezvous)
        append("&label=").append(URLEncoder.encode(label, "UTF-8"))
        for (relay in relays) append("&relay=").append(URLEncoder.encode(relay, "UTF-8"))
    }

    companion object {
        const val SCHEME = "heartwood-unlock"
        const val MAX_LABEL_BYTES = 16
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val HEX32 = Regex("^[0-9a-f]{32}$")

        /** Trims [raw] to the board's label limit without splitting a UTF-8 sequence. */
        fun fitLabel(raw: String): String {
            val trimmed = raw.trim().ifEmpty { "phone" }
            var end = trimmed.length
            while (trimmed.substring(0, end).toByteArray(Charsets.UTF_8).size > MAX_LABEL_BYTES) end--
            // Never leave half of a surrogate pair behind.
            if (end > 0 && Character.isHighSurrogate(trimmed[end - 1])) end--
            return trimmed.substring(0, end)
        }

        fun parse(text: String): EnrolmentCode? {
            val prefix = "$SCHEME:enrol?"
            if (!text.startsWith(prefix)) return null
            val params = text.removePrefix(prefix).split('&').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) null else pair.substring(0, eq) to runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrNull()
            }
            fun one(name: String) = params.singleOrNull { it.first == name }?.second
            if (one("v") != "1") return null
            val p = one("p")?.takeIf { HEX64.matches(it) } ?: return null
            val r = one("r")?.takeIf { HEX32.matches(it) } ?: return null
            val label = one("label")?.takeIf { it.isNotBlank() && it.toByteArray().size <= MAX_LABEL_BYTES } ?: return null
            val relays = params.filter { it.first == "relay" }.map { it.second ?: return null }
            if (relays.isEmpty() || relays.any { !isRelayUrl(it) }) return null
            return EnrolmentCode(p, r, label, relays)
        }
    }
}

internal fun isRelayUrl(url: String): Boolean =
    (url.startsWith("wss://") || url.startsWith("ws://")) && url.length in 7..256 && url.none { it.isWhitespace() }

/**
 * The hand-off event's content: the board's enrolment answer, passed through untouched
 * (`{"id", "ephemeral_pubkey", "sealed"}`), so Sapwood never needs to understand it.
 */
@Serializable
data class HandOffEnvelope(
    val id: Long,
    @kotlinx.serialization.SerialName("ephemeral_pubkey") val ephemeralPubkeyHex: String,
    val sealed: String,
)

/** The sealed hand-off's plaintext, as the phone opens it: `{v:1, id, s, relays}`. */
class HandOff(val id: Long, val slotSecret: ByteArray, val relays: List<String>) {
    fun wipe() = slotSecret.fill(0)
}

object HandOffParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    @Serializable
    private data class Plain(val v: Int, val id: Long, val s: String, val relays: List<String>)

    fun envelope(content: String): HandOffEnvelope? =
        runCatching { json.decodeFromString<HandOffEnvelope>(content) }.getOrNull()
            ?.takeIf { HEX64.matches(it.ephemeralPubkeyHex) && it.sealed.isNotEmpty() && it.id in 0..0xFFFF_FFFFL }

    /**
     * Parses the decrypted hand-off and checks it is the one the envelope announced. Null for a
     * wrong version, a malformed secret, an id that does not match, or a relay list the phone could
     * not use.
     */
    fun plain(decrypted: String, envelope: HandOffEnvelope): HandOff? {
        val plain = runCatching { json.decodeFromString<Plain>(decrypted) }.getOrNull() ?: return null
        if (plain.v != 1 || plain.id != envelope.id || !HEX64.matches(plain.s)) return null
        val relays = plain.relays.filter(::isRelayUrl)
        if (relays.isEmpty()) return null
        return HandOff(plain.id, plain.s.hexToBytesOrNull() ?: return null, relays)
    }
}

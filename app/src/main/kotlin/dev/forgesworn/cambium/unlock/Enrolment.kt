package dev.forgesworn.cambium.unlock

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer

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

        /**
         * Fits [raw] to the board's label limit as printable ASCII (0x20-0x7E), which is all the
         * firmware now accepts for a label -- on the cable as well as over the relay, so this must
         * hold whatever the board's own model name string throws at it. NFKD decomposition first,
         * so an accented letter or a fullwidth character (e.g. "é", "ｓｗｉｍ") reduces to its plain
         * ASCII base rather than being dropped outright; anything left outside the printable range
         * (control characters, emoji, combining marks NFKD peeled off) is simply removed. A label
         * built entirely of non-ASCII input still falls back to "phone", matching the empty-input
         * case.
         */
        fun fitLabel(raw: String): String {
            val ascii = Normalizer.normalize(raw, Normalizer.Form.NFKD).filter { it.code in 0x20..0x7E }
            val trimmed = ascii.trim().ifEmpty { "phone" }
            // Every remaining character is single-byte ASCII, so length and byte count agree.
            return trimmed.take(MAX_LABEL_BYTES).trim().ifEmpty { "phone" }
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

/**
 * The six characters the owner compares between the phone and Sapwood (or the bench script) after
 * enrolment, shown as "9B6 164". It is spoken-token's derivation (forgesworn/spoken-token,
 * `deriveToken(secret, context, 0, { format: 'hex', length: 6 })`, i.e. the first three bytes of
 * HMAC-SHA256(secret, utf8(context) || counter_be32)) with the board's one-off hand-off key as the
 * secret. The board draws that key fresh for every enrolment, so an answer raced in by someone who
 * saw the enrolment code matches about one time in 16.7 million. Sapwood uses spoken-token itself;
 * the bench script and this are ports held to vectors it produced.
 */
fun checkCode(ephemeralPubkeyHex: String): String? {
    val key = ephemeralPubkeyHex.hexToBytesOrNull()?.takeIf { it.size == 32 } ?: return null
    val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
    val digest = mac.doFinal(CHECK_CONTEXT.toByteArray(Charsets.UTF_8) + ByteArray(4))
    val hex = digest.copyOfRange(0, 3).joinToString("") { "%02X".format(it) }
    return "${hex.substring(0, 3)} ${hex.substring(3)}"
}

private const val CHECK_CONTEXT = "heartwood-unlock:enrol-check"

/** How many words the request code has. */
const val REQUEST_CODE_WORDS = 5

/**
 * The five words the board shows on its "ADD UNLOCK PHONE" card before the press, derived from
 * this phone's own one-off enrolment pubkey P: spoken-token's `deriveToken(P,
 * 'heartwood-unlock:enrol-request', 0, { format: 'words', count: 5 })` (55 bits), i.e. word `i` is
 * `WORDLIST[uint16_be(digest[2i..2i+2]) % 2048]` of `HMAC-SHA256(P, utf8(context) || counter_be32)`.
 * Cambium must show the same words: the phone is the trusted side (it made P), so the owner
 * compares the board's words against *this* screen, not a browser's copy of the enrolment code,
 * which proves nothing. Held to the firmware's own frozen vectors in `EnrolmentTest`.
 */
fun requestWords(enrolPubkeyHex: String): String? {
    val key = enrolPubkeyHex.hexToBytesOrNull()?.takeIf { it.size == 32 } ?: return null
    val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
    val digest = mac.doFinal(REQUEST_CONTEXT.toByteArray(Charsets.UTF_8) + ByteArray(4))
    return (0 until REQUEST_CODE_WORDS).joinToString(" ") { i ->
        val index = ((digest[2 * i].toInt() and 0xFF) shl 8) or (digest[2 * i + 1].toInt() and 0xFF)
        SpokenWords.WORDLIST[index % SpokenWords.WORDLIST.size]
    }
}

private const val REQUEST_CONTEXT = "heartwood-unlock:enrol-request"

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

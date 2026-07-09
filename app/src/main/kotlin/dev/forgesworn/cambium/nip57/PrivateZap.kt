package dev.forgesworn.cambium.nip57

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Recipient-side decoding of DIP-03 "private zaps" (https://github.com/damus-io/dips/blob/master/03.md)
 * -- a de facto convention (not in the core NIP-57 spec, which explicitly defers zap privacy to
 * "future work") used by Damus, Amethyst and Amber's `decrypt_zap_event`.
 *
 * A private zap request (kind 9734) carries an `anon` tag: two bech32 strings joined by `_` --
 * `pzap1...` (the NIP-04 ciphertext bytes) and `iv1...` (the IV bytes) -- encrypted with NIP-04
 * between the zap request's own (ephemeral) key and the recipient's pubkey. [decodeAnonTag]
 * re-encodes those bech32 parts into the ordinary NIP-04 wire format
 * (`<base64 ciphertext>?iv=<base64 iv>`) so it can be forwarded as a plain nip04_decrypt against
 * the event's own `pubkey` -- the recipient's real identity key decrypts against that same
 * ephemeral pubkey, mirroring how it was encrypted. The plaintext that comes back is a kind 9733
 * event; [isValidPrivateZapEvent] is the minimal check that it actually is one (does not verify
 * its signature).
 *
 * Deliberately no recipient-vs-sender pre-check: the paired identity's real key only succeeds at
 * this ECDH when it is actually the recipient. If it is the sender instead, the decrypt just fails
 * (an ordinary, correctly-cached deterministic failure -- see [isValidPrivateZapEvent]'s caller),
 * which is simpler and no less correct than trying to detect that case up front.
 *
 * Pure Kotlin: no Android, so this stays JVM-testable, matching BunkerUri.kt/QrPairingScan.kt/
 * DecryptCache.kt.
 *
 * **Known limitation, not implemented**: DIP-03 also describes *sender*-side decoding, where the
 * sender of an anonymous zap can later recognise their own private zaps by regenerating the
 * ephemeral keypair via `sha256(senderPrivkeyBytes + noteId + createdAt)`. That derivation needs
 * the raw private key bytes fed directly into a hash function -- not an operation NIP-46 remote
 * signing exposes (Heartwood only exposes get_public_key/sign_event/nip04/nip44, never the key
 * material itself), so it is impossible to do through Cambium regardless of implementation effort.
 * Only the recipient path above is implemented. See CLAUDE.md and README.md.
 */
sealed interface ZapDecodeResult {
    /** [counterpartyPubkeyHex] is the outer (ephemeral) event's own `pubkey` -- pass this as the
     * nip04_decrypt counterparty. [nip04Payload] is ready to forward to nip04_decrypt as-is.
     * [anonTagValue] is the raw, still-bech32-encoded `anon` tag content -- the natural cache key,
     * since it is exactly the encrypted material, independent of the wrapper event's `id`/`sig`. */
    data class Forward(
        val counterpartyPubkeyHex: String,
        val nip04Payload: String,
        val anonTagValue: String,
    ) : ZapDecodeResult

    /** Structurally broken input: not valid JSON, or missing a `pubkey` field. Deterministic --
     * never worth a relay round trip. */
    data class Malformed(val reason: String) : ZapDecodeResult

    /** Valid JSON, but not a kind-9734 zap request. */
    data object NotAZapRequest : ZapDecodeResult

    /** A kind-9734 request with no `anon` tag: an ordinary public zap, nothing to decrypt. */
    data object NoAnonTag : ZapDecodeResult

    /** An `anon` tag that isn't `<pzap-bech32>_<iv-bech32>`, or fails bech32 checksum/hrp. */
    data class MalformedAnon(val reason: String) : ZapDecodeResult
}

object PrivateZap {

    fun decodeAnonTag(eventJson: String): ZapDecodeResult {
        val event = runCatching { Json.parseToJsonElement(eventJson).jsonObject }
            .getOrElse { return ZapDecodeResult.Malformed("event is not valid JSON") }

        val kind = runCatching { event["kind"]?.jsonPrimitive?.intOrNull }.getOrNull()
        if (kind != ZAP_REQUEST_KIND) {
            return ZapDecodeResult.NotAZapRequest
        }

        val pubkey = runCatching { event["pubkey"]?.jsonPrimitive?.content }.getOrNull()
            ?: return ZapDecodeResult.Malformed("event has no pubkey")

        val tags = event["tags"]?.jsonArray ?: return ZapDecodeResult.NoAnonTag
        val anonValue = runCatching {
            tags.asSequence()
                .mapNotNull { it as? JsonArray }
                .firstOrNull { tag -> tag.getOrNull(0)?.jsonPrimitive?.content == "anon" }
                ?.getOrNull(1)?.jsonPrimitive?.content
        }.getOrNull() ?: return ZapDecodeResult.NoAnonTag

        val separator = anonValue.indexOf('_')
        if (separator <= 0 || separator == anonValue.length - 1) {
            return ZapDecodeResult.MalformedAnon("anon tag is not <pzap-bech32>_<iv-bech32>")
        }

        val ciphertextBytes = Bech32.decode(anonValue.substring(0, separator), expectedHrp = "pzap")
            .getOrElse { return ZapDecodeResult.MalformedAnon("bad pzap bech32: ${it.message}") }
        val ivBytes = Bech32.decode(anonValue.substring(separator + 1), expectedHrp = "iv")
            .getOrElse { return ZapDecodeResult.MalformedAnon("bad iv bech32: ${it.message}") }

        val encoder = Base64.getEncoder()
        val nip04Payload = "${encoder.encodeToString(ciphertextBytes)}?iv=${encoder.encodeToString(ivBytes)}"
        return ZapDecodeResult.Forward(pubkey, nip04Payload, anonValue)
    }

    /** True when [plaintext] parses as JSON with `"kind": 9733` -- does not verify its signature. */
    fun isValidPrivateZapEvent(plaintext: String): Boolean = runCatching {
        Json.parseToJsonElement(plaintext).jsonObject["kind"]?.jsonPrimitive?.intOrNull == PRIVATE_ZAP_KIND
    }.getOrDefault(false)

    const val ZAP_REQUEST_KIND = 9734
    const val PRIVATE_ZAP_KIND = 9733
}

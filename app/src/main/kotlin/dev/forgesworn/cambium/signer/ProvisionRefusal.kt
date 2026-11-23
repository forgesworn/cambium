package dev.forgesworn.cambium.signer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Says why Heartwood refused a rendezvous provision, so the owner can tell a denied or timed-out
 * card from a connection that is not allowed the method. It matches the board's exact error strings
 * (heartwood-esp32 `firmware/src/nip46_handler.rs`, and `common/src/escalate.rs` for the
 * away-approval refusal). Anything else, including wording a later firmware introduces, gets
 * [GENERIC]: text from the board is never shown as received.
 *
 * Every message is worded as a refusal [isPolicyRefusal] recognises. The board did answer, so
 * connection health must keep showing it as connected rather than unreachable. Messages stay short
 * because Android 12 and later cut a text toast to two lines.
 */
internal object ProvisionRefusal {
    const val GENERIC = "Heartwood refused rendezvous provisioning"

    private val BY_BOARD_ERROR = mapOf(
        "user denied" to "Denied on Heartwood",
        "timeout" to "Heartwood refused: the button was not held in time",
        "unauthorised" to "Heartwood refused: this connection is not allowed rendezvous keys",
        "this request must be approved at the device; a guardian verdict cannot answer it" to
            "Heartwood refused: turn off Approve from my phone for this connection",
        // Firmware before 0.18.0-beta.9 does not know the method.
        "unknown method" to "Heartwood refused: its firmware is too old for rendezvous keys",
        "trusted clock unavailable" to "Heartwood refused: its clock is not set yet. Try again shortly",
        "rendezvous expiry must be after signer time and within ten minutes" to
            "Heartwood refused: its clock does not match this request's expiry",
        "rendezvous provision receipt store full" to
            "Heartwood refused: too many recent provisions. Try again in ten minutes",
        "rendezvous provision already completed" to "Heartwood refused: this request was already completed",
        "rendezvous provision nonce already used" to "Heartwood refused: this request's nonce was already used",
    )

    /** [error] is the response's `error` member, which NIP-46 carries as a plain string. */
    fun messageFor(error: JsonElement?): String =
        (error as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(BY_BOARD_ERROR::get) ?: GENERIC
}

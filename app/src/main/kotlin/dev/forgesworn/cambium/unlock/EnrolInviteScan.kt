package dev.forgesworn.cambium.unlock

/**
 * Turns a raw QR scan result into an invite decision, the reverse-direction sibling of
 * [dev.forgesworn.cambium.pairing.QrPairingScan]: pure Kotlin, no Android or zxing, so the zxing
 * call itself stays in [UnlockEnrolActivity].
 */
sealed interface EnrolInviteScanResult {
    data class Accepted(val invite: InviteUri) : EnrolInviteScanResult
    data class Rejected(val message: String) : EnrolInviteScanResult
    /** The user backed out of the scanner: not an error, nothing should be shown. */
    data object Cancelled : EnrolInviteScanResult
}

object EnrolInviteScan {

    const val NOT_AN_INVITE = "That QR is not a Sapwood invite."
    const val WRONG_DIRECTION_BUNKER =
        "That link is for pairing an app with Sapwood, not an unlock invite: go to Sapwood's \"Add a phone\" step and scan that code instead."
    const val WRONG_DIRECTION_ENROL_CODE =
        "That is this phone's own unlock code, not Sapwood's invite: on Sapwood, click \"Add a phone\" and scan the code it shows instead."

    private const val BUNKER_SCHEME = "bunker://"
    private const val NOSTRCONNECT_SCHEME = "nostrconnect://"
    private const val ENROL_PREFIX = "${EnrolmentCode.SCHEME}:enrol?"

    /**
     * [rawContents] is the scanning library's result content: `null` means the user cancelled the
     * scan, an empty/blank string means a QR was read but encoded nothing usable.
     */
    fun evaluate(rawContents: String?, nowSecs: Long = System.currentTimeMillis() / 1000): EnrolInviteScanResult {
        if (rawContents == null) return EnrolInviteScanResult.Cancelled

        val trimmed = rawContents.trim()
        if (trimmed.isEmpty()) return EnrolInviteScanResult.Rejected(NOT_AN_INVITE)

        if (trimmed.startsWith(BUNKER_SCHEME, ignoreCase = true) || trimmed.startsWith(NOSTRCONNECT_SCHEME, ignoreCase = true)) {
            // Sapwood's own "Connect an app" bunker link (or its client-initiated sibling): the
            // pairing direction, not the unlock-invite direction this screen expects.
            return EnrolInviteScanResult.Rejected(WRONG_DIRECTION_BUNKER)
        }

        if (trimmed.startsWith(ENROL_PREFIX, ignoreCase = true)) {
            // This phone's own "heartwood-unlock:enrol?..." code, or another phone's: the
            // opposite direction from Sapwood's invite.
            return EnrolInviteScanResult.Rejected(WRONG_DIRECTION_ENROL_CODE)
        }

        val invite = InviteUri.parse(trimmed, nowSecs) ?: return EnrolInviteScanResult.Rejected(NOT_AN_INVITE)
        return EnrolInviteScanResult.Accepted(invite)
    }
}

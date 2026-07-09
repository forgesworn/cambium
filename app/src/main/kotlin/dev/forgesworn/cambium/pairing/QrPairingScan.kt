package dev.forgesworn.cambium.pairing

/**
 * Turns a raw QR scan result into a pairing decision. Pure Kotlin (no Android, no zxing): the
 * scanning library's result type is a plain `String?`, so this stays JVM-testable the same way
 * [BunkerUriParser] does; the zxing calls themselves live only in `MainActivity`.
 */
sealed interface QrScanResult {
    data class Accepted(val uri: BunkerUri) : QrScanResult
    data class Rejected(val message: String) : QrScanResult
    /** The user backed out of the scanner: not an error, nothing should be shown. */
    data object Cancelled : QrScanResult
}

object QrPairingScan {

    const val NOT_A_BUNKER_LINK = "That QR is not a bunker link."
    const val WRONG_DIRECTION =
        "That link is for Sapwood, not Cambium: scan the bunker link from Sapwood's Connect an app step instead."

    private const val NOSTRCONNECT_SCHEME = "nostrconnect://"

    /**
     * [rawContents] is the scanning library's result content: `null` means the user cancelled the
     * scan (zxing returns no content in that case), an empty/blank string means a QR was read but
     * encoded nothing usable.
     */
    fun evaluate(rawContents: String?): QrScanResult {
        if (rawContents == null) return QrScanResult.Cancelled

        val trimmed = rawContents.trim()
        if (trimmed.isEmpty()) return QrScanResult.Rejected(NOT_A_BUNKER_LINK)

        if (trimmed.startsWith(NOSTRCONNECT_SCHEME, ignoreCase = true)) {
            // nostrconnect:// is the client-initiated direction (a client presents this for a
            // remote signer to scan). Cambium only ever consumes the signer-initiated bunker://
            // direction, which is what Sapwood's "Connect an app" step produces.
            return QrScanResult.Rejected(WRONG_DIRECTION)
        }

        return when (val parsed = BunkerUriParser.parse(trimmed)) {
            is BunkerUriResult.Valid -> QrScanResult.Accepted(parsed.uri)
            is BunkerUriResult.Invalid -> QrScanResult.Rejected(NOT_A_BUNKER_LINK)
        }
    }
}

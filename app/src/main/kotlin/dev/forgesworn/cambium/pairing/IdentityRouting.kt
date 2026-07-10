package dev.forgesworn.cambium.pairing

import dev.forgesworn.cambium.nip57.Bech32

/**
 * Decides which [Pairing] (which paired Heartwood identity) a NIP-55 request should route to,
 * now that Cambium can hold more than one. Pure Kotlin -- no Android -- so the precedence rules
 * and `current_user` normalisation are JVM-testable independent of `SignerActivity`/`SignerProvider`.
 *
 * Precedence, matching Amber's own `current_user` convention: an explicit `current_user` extra
 * wins outright if it names a pairing we have; otherwise the calling app's remembered
 * [AppPermission.boundIdentityPubkeyHex] is used; otherwise the sole pairing, if there is exactly
 * one. Deliberately never guesses beyond that: a `current_user` that names an identity we do not
 * have is [Result.UnknownCurrentUser] rather than silently falling through to the bound identity
 * or the sole pairing -- signing with a different identity than the caller explicitly asked for
 * would be worse than refusing. Two or more pairings with neither a matching `current_user` nor a
 * binding is [Result.Ambiguous] for the same reason.
 */
object IdentityRouting {

    sealed interface Result {
        data class Resolved(val pairing: Pairing) : Result
        data object UnknownCurrentUser : Result
        data object Ambiguous : Result
    }

    fun resolve(rawCurrentUser: String?, boundIdentityPubkeyHex: String?, pairings: List<Pairing>): Result {
        if (!rawCurrentUser.isNullOrBlank()) {
            val hex = normaliseCurrentUser(rawCurrentUser)
            val matched = hex?.let { h -> pairings.firstOrNull { it.signerPubkeyHex.equals(h, ignoreCase = true) } }
            return matched?.let { Result.Resolved(it) } ?: Result.UnknownCurrentUser
        }

        if (boundIdentityPubkeyHex != null) {
            pairings.firstOrNull { it.signerPubkeyHex.equals(boundIdentityPubkeyHex, ignoreCase = true) }
                ?.let { return Result.Resolved(it) }
            // Bound identity no longer exists -- PairingStore.removePairing forgets any app bound
            // to the identity it removes, so this should not normally happen. Fall through rather
            // than trust a binding that no longer resolves to anything.
        }

        return pairings.singleOrNull()?.let { Result.Resolved(it) } ?: Result.Ambiguous
    }

    /**
     * NIP-55's `current_user` extra may be an npub or 64-character hex pubkey (Amber accepts
     * both). Returns lowercase hex, or `null` if [raw] is neither -- garbage input is
     * indistinguishable from "names an identity we don't have" as far as [resolve] is concerned,
     * which is the safe direction: both refuse rather than guess.
     */
    fun normaliseCurrentUser(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length == HEX_PUBKEY_LENGTH && trimmed.all { it.isHexDigit() }) return trimmed.lowercase()
        return Bech32.decode(trimmed, expectedHrp = "npub").getOrNull()
            ?.takeIf { it.size == PUBKEY_BYTES }
            ?.toHex()
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val HEX_PUBKEY_LENGTH = 64
    private const val PUBKEY_BYTES = 32
}

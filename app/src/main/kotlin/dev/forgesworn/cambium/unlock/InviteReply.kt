package dev.forgesworn.cambium.unlock

/**
 * The shaping of an enrol-invite reply event (spec v1) that has nothing to do with cryptography:
 * exactly two tags, `h` (the invite's own rendezvous `ri`) and `expiration` (NIP-40, the invite's
 * own `x`), and nothing else. Pure Kotlin, no rust-nostr, shared between the production builder
 * (`signer/UnlockRelay.kt`'s `UnlockNostr.inviteReplyEvent`, which turns these into real `Tag`s,
 * NIP-44-encrypts the plaintext, and signs, all through rust-nostr) and `EnrolInviteVectorTest`
 * (which checks the same tag values against the shared Sapwood/Cambium vector), so both are held
 * to one copy of this shape rather than two that could quietly drift apart.
 *
 * The actual encryption is deliberately not here: rust-nostr's Kotlin bindings expose no way to
 * pin the NIP-44 nonce and cannot load on the host JVM at all (native code per ABI -- see
 * `pairing/BunkerUri.kt`'s class doc), so production and the vector test cannot share that step.
 * The vector test instead holds a test-only reference NIP-44 v2 implementation
 * (`src/test/kotlin/.../Nip44.kt`, `Secp256k1.kt`) to the vector directly.
 */
object InviteReplyBuilder {
    fun tags(invite: InviteUri): List<Pair<String, String>> =
        listOf("h" to invite.rendezvous, "expiration" to invite.expiresAtSecs.toString())
}

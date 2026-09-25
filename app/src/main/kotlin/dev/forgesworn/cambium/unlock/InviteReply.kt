package dev.forgesworn.cambium.unlock

import dev.forgesworn.cambium.toHex
import java.security.SecureRandom

/**
 * Cambium's answer to a Sapwood invite ([InviteUri]): everything needed to build and sign the
 * kind-24137 reply event, except the actual signing (rust-nostr, native, see
 * `signer/UnlockRelay.kt`'s `inviteReplyEvent`) -- this class stays pure Kotlin so it can be held
 * to the shared Sapwood/Cambium test vector on the host JVM.
 *
 * [throwawaySecret] is the reply's author: a fresh key with no other purpose, never persisted.
 * The caller is responsible for `fill(0)`-ing it once the event is signed -- this class only
 * builds the plaintext-to-ciphertext transform, so it cannot itself know when signing is done.
 * [content] is `NIP-44 v2 encrypt(EnrolmentCode.encode())` to the invite's `inv` key; [rendezvous]
 * and [expiresAtSecs] are exactly the invite's `ri`/`x`, carried through as the reply event's only
 * tags (`h`, `expiration`).
 */
class InviteReply(
    val throwawaySecret: ByteArray,
    val throwawayPubkeyHex: String,
    val content: String,
    val rendezvous: String,
    val expiresAtSecs: Long,
)

object InviteReplyBuilder {

    /**
     * Builds the reply to [invite], sealing [enrolmentCode] (an [EnrolmentCode.encode] string) so
     * only whoever holds `inv`'s secret half can read it. [throwawaySecret] and [nonce] are
     * injectable so a test can pin them to the shared vector; production callers take the
     * defaults, a fresh 32 bytes of each from [SecureRandom]. Null only if the invite's `k` is not
     * a usable public key (already checked by [InviteUri.parse]'s hex64 rule, so this should not
     * happen for a value that parsed).
     */
    fun build(
        invite: InviteUri,
        enrolmentCode: String,
        throwawaySecret: ByteArray = randomBytes(32),
        nonce: ByteArray = randomBytes(32),
    ): InviteReply? {
        val pubkeyHex = Secp256k1.publicKeyXOnly(throwawaySecret).toHex()
        val content = Nip44.encrypt(throwawaySecret, invite.inviterPubkeyHex, enrolmentCode, nonce) ?: return null
        return InviteReply(throwawaySecret, pubkeyHex, content, invite.rendezvous, invite.expiresAtSecs)
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
}

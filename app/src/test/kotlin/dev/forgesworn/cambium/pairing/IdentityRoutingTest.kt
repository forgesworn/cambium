package dev.forgesworn.cambium.pairing

import dev.forgesworn.cambium.nip57.Bech32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class IdentityRoutingTest {

    private val pubkeyHexA = "a1".repeat(32)
    private val pubkeyHexB = "b2".repeat(32)
    private val pubkeyHexC = "c3".repeat(32)

    private val pairingA = pairing(pubkeyHexA)
    private val pairingB = pairing(pubkeyHexB)

    private fun pairing(pubkeyHex: String) = Pairing(
        signerPubkeyHex = pubkeyHex,
        relays = listOf("wss://relay.example"),
        secret = null,
        clientSecretKeyHex = "d".repeat(64),
        clientPublicKeyHex = "e".repeat(64),
    )

    /** Real bech32 round trip via [Bech32.encode] rather than a hand-typed npub literal, so a
     * transcription slip here can't silently make the normalisation test meaningless. */
    private fun npubFor(pubkeyHex: String): String {
        val bytes = ByteArray(pubkeyHex.length / 2) { i ->
            pubkeyHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return Bech32.encode("npub", bytes)
    }

    // -- normaliseCurrentUser --

    @Test
    fun `a 64-character hex current_user normalises to lowercase hex`() {
        assertEquals(pubkeyHexA, IdentityRouting.normaliseCurrentUser(pubkeyHexA.uppercase()))
    }

    @Test
    fun `a valid npub current_user normalises to its hex form`() {
        assertEquals(pubkeyHexA, IdentityRouting.normaliseCurrentUser(npubFor(pubkeyHexA)))
    }

    @Test
    fun `garbage current_user normalises to null`() {
        assertNull(IdentityRouting.normaliseCurrentUser("not-an-identity"))
    }

    @Test
    fun `blank or absent current_user normalises to null`() {
        assertNull(IdentityRouting.normaliseCurrentUser(null))
        assertNull(IdentityRouting.normaliseCurrentUser("   "))
    }

    // -- resolve: precedence --

    @Test
    fun `an explicit current_user match wins over a different bound identity`() {
        val result = IdentityRouting.resolve(pubkeyHexB, boundIdentityPubkeyHex = pubkeyHexA, pairings = listOf(pairingA, pairingB))

        assertEquals(pubkeyHexB, assertIs<IdentityRouting.Result.Resolved>(result).pairing.signerPubkeyHex)
    }

    @Test
    fun `current_user naming an identity we don't have is UnknownCurrentUser, never falls through to the binding`() {
        val result = IdentityRouting.resolve(pubkeyHexC, boundIdentityPubkeyHex = pubkeyHexA, pairings = listOf(pairingA, pairingB))

        assertIs<IdentityRouting.Result.UnknownCurrentUser>(result)
    }

    @Test
    fun `garbage current_user is also UnknownCurrentUser, not silently ignored`() {
        val result = IdentityRouting.resolve("garbage", boundIdentityPubkeyHex = pubkeyHexA, pairings = listOf(pairingA, pairingB))

        assertIs<IdentityRouting.Result.UnknownCurrentUser>(result)
    }

    @Test
    fun `with no current_user, the bound identity is used`() {
        val result = IdentityRouting.resolve(null, boundIdentityPubkeyHex = pubkeyHexB, pairings = listOf(pairingA, pairingB))

        assertEquals(pubkeyHexB, assertIs<IdentityRouting.Result.Resolved>(result).pairing.signerPubkeyHex)
    }

    @Test
    fun `with no current_user and no binding, the sole pairing is used`() {
        val result = IdentityRouting.resolve(null, boundIdentityPubkeyHex = null, pairings = listOf(pairingA))

        assertEquals(pubkeyHexA, assertIs<IdentityRouting.Result.Resolved>(result).pairing.signerPubkeyHex)
    }

    @Test
    fun `with no current_user, no binding, and more than one pairing, routing is ambiguous`() {
        val result = IdentityRouting.resolve(null, boundIdentityPubkeyHex = null, pairings = listOf(pairingA, pairingB))

        assertIs<IdentityRouting.Result.Ambiguous>(result)
    }

    @Test
    fun `a binding that no longer matches any pairing does not resolve to a stale identity`() {
        val result = IdentityRouting.resolve(null, boundIdentityPubkeyHex = pubkeyHexC, pairings = listOf(pairingA, pairingB))

        assertIs<IdentityRouting.Result.Ambiguous>(result)
    }

    @Test
    fun `a binding that no longer matches still falls back to the sole remaining pairing`() {
        val result = IdentityRouting.resolve(null, boundIdentityPubkeyHex = pubkeyHexC, pairings = listOf(pairingA))

        assertEquals(pubkeyHexA, assertIs<IdentityRouting.Result.Resolved>(result).pairing.signerPubkeyHex)
    }

    @Test
    fun `no pairings at all is ambiguous, never a crash`() {
        val result = IdentityRouting.resolve(null, boundIdentityPubkeyHex = null, pairings = emptyList())

        assertIs<IdentityRouting.Result.Ambiguous>(result)
    }

    @Test
    fun `current_user comparison is case-insensitive against the stored signer pubkey`() {
        val result = IdentityRouting.resolve(pubkeyHexA.uppercase(), boundIdentityPubkeyHex = null, pairings = listOf(pairingA, pairingB))

        assertEquals(pubkeyHexA, assertIs<IdentityRouting.Result.Resolved>(result).pairing.signerPubkeyHex)
    }
}

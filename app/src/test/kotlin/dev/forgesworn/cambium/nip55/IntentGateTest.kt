package dev.forgesworn.cambium.nip55

import dev.forgesworn.cambium.pairing.AppPermission
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.pairing.Pairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IntentGateTest {

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

    private val approvedForA = AppPermission(AppPermissionState.APPROVED, pubkeyHexA)
    private val denied = AppPermission(AppPermissionState.DENIED, boundIdentityPubkeyHex = null)

    private fun request(currentUser: String? = null): Nip55Request =
        Nip55Request.GetPublicKey(id = "req-1", currentUser = currentUser)

    // -- plan --

    @Test
    fun `an unparsable request is rejected as malformed, before anything else is considered`() {
        val plan = IntentGate.plan(approvedForA, listOf(pairingA), parsed = null, canAsk = true)
        assertEquals(IntentGate.RejectReason.MALFORMED_REQUEST, assertIs<IntentGate.Plan.Reject>(plan).reason)

        val unknownCallerNothingPaired = IntentGate.plan(null, emptyList(), parsed = null, canAsk = true)
        assertEquals(
            IntentGate.RejectReason.MALFORMED_REQUEST,
            assertIs<IntentGate.Plan.Reject>(unknownCallerNothingPaired).reason,
        )
    }

    @Test
    fun `nothing paired is rejected, for known and unknown callers alike`() {
        for (permission in listOf(approvedForA, denied, null)) {
            val plan = IntentGate.plan(permission, emptyList(), request(), canAsk = true)
            assertEquals(IntentGate.RejectReason.NOTHING_PAIRED, assertIs<IntentGate.Plan.Reject>(plan).reason)
        }
    }

    @Test
    fun `a remembered denial is rejected without any routing, even with a resolvable current_user`() {
        val plan = IntentGate.plan(denied, listOf(pairingA, pairingB), request(currentUser = pubkeyHexA), canAsk = true)
        assertEquals(IntentGate.RejectReason.DENIED_CALLER, assertIs<IntentGate.Plan.Reject>(plan).reason)
    }

    @Test
    fun `a caller with no remembered choice is asked`() {
        val plan = IntentGate.plan(null, listOf(pairingA), request(), canAsk = true)
        assertIs<IntentGate.Plan.AskUser>(plan)
    }

    @Test
    fun `an approved caller forwards to its bound identity`() {
        val plan = IntentGate.plan(approvedForA, listOf(pairingA, pairingB), request(), canAsk = true)
        assertEquals(pubkeyHexA, assertIs<IntentGate.Plan.Forward>(plan).pairing.signerPubkeyHex)
    }

    @Test
    fun `an explicit current_user match wins over the binding`() {
        val plan = IntentGate.plan(approvedForA, listOf(pairingA, pairingB), request(currentUser = pubkeyHexB), canAsk = true)
        assertEquals(pubkeyHexB, assertIs<IntentGate.Plan.Forward>(plan).pairing.signerPubkeyHex)
    }

    @Test
    fun `an approved caller naming an identity we don't have is asked, never guessed`() {
        val plan = IntentGate.plan(approvedForA, listOf(pairingA, pairingB), request(currentUser = pubkeyHexC), canAsk = true)
        assertIs<IntentGate.Plan.AskUser>(plan)
    }

    @Test
    fun `the same unresolved routing on a window that cannot ask is rejected instead`() {
        val plan = IntentGate.plan(approvedForA, listOf(pairingA, pairingB), request(currentUser = pubkeyHexC), canAsk = false)
        assertEquals(IntentGate.RejectReason.UNRESOLVED_IDENTITY, assertIs<IntentGate.Plan.Reject>(plan).reason)
    }

    @Test
    fun `an approved caller with no binding and several pairings is ambiguous, so it asks`() {
        val unbound = AppPermission(AppPermissionState.APPROVED, boundIdentityPubkeyHex = null)
        val plan = IntentGate.plan(unbound, listOf(pairingA, pairingB), request(), canAsk = true)
        assertIs<IntentGate.Plan.AskUser>(plan)
    }

    // -- silentFor --

    @Test
    fun `a denied caller's rejection is silent`() {
        val plan = IntentGate.plan(denied, listOf(pairingA), request(), canAsk = true)
        assertTrue(IntentGate.silentFor(denied, plan))
    }

    @Test
    fun `an approved caller whose routing resolves is silent`() {
        val plan = IntentGate.plan(approvedForA, listOf(pairingA), request(), canAsk = true)
        assertTrue(IntentGate.silentFor(approvedForA, plan))
    }

    @Test
    fun `an approved caller that needs the sheet is not silent`() {
        val plan = IntentGate.plan(approvedForA, listOf(pairingA, pairingB), request(currentUser = pubkeyHexC), canAsk = true)
        assertFalse(IntentGate.silentFor(approvedForA, plan))
    }

    @Test
    fun `a caller with no remembered choice is never silent, even when the plan is a rejection`() {
        // Nothing paired: the visible window is what carries the not-paired toast -- a brand-new
        // user's first attempt must not be a silent no-op.
        val plan = IntentGate.plan(null, emptyList(), request(), canAsk = true)
        assertIs<IntentGate.Plan.Reject>(plan)
        assertFalse(IntentGate.silentFor(null, plan))
    }

    @Test
    fun `an approved caller's rejection with nothing paired stays silent`() {
        val plan = IntentGate.plan(approvedForA, emptyList(), request(), canAsk = true)
        assertIs<IntentGate.Plan.Reject>(plan)
        assertTrue(IntentGate.silentFor(approvedForA, plan))
    }
}

package dev.forgesworn.cambium.nip55

import dev.forgesworn.cambium.pairing.AppPermission
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.pairing.PermissionScope
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
    fun `an explicit current_user naming the caller's own bound identity still forwards silently`() {
        val plan = IntentGate.plan(approvedForA, listOf(pairingA, pairingB), request(currentUser = pubkeyHexA), canAsk = true)
        assertEquals(pubkeyHexA, assertIs<IntentGate.Plan.Forward>(plan).pairing.signerPubkeyHex)
    }

    @Test
    fun `an explicit current_user naming a different paired identity asks, never forwards silently`() {
        // The approval was granted for identity A only; honouring B without asking would let any
        // approved app reach every paired identity. The sheet's picker defaults to the requested
        // identity, so Approve there rebinds deliberately, with the rebind hint visible.
        val plan = IntentGate.plan(approvedForA, listOf(pairingA, pairingB), request(currentUser = pubkeyHexB), canAsk = true)
        assertIs<IntentGate.Plan.AskUser>(plan)
        assertFalse(IntentGate.silentFor(approvedForA, plan))
    }

    @Test
    fun `the same cross-identity current_user on a window that cannot ask is rejected instead`() {
        val plan = IntentGate.plan(approvedForA, listOf(pairingA, pairingB), request(currentUser = pubkeyHexB), canAsk = false)
        assertEquals(IntentGate.RejectReason.UNRESOLVED_IDENTITY, assertIs<IntentGate.Plan.Reject>(plan).reason)
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
    @Test
    fun `expired approval asks visibly and rejects on an invisible window`() {
        val expired = approvedForA.copy(expiresAtMillis = 100L)
        val visible = IntentGate.plan(expired, listOf(pairingA), request(), canAsk = true, nowMillis = 100L)
        assertIs<IntentGate.Plan.AskUser>(visible)
        assertFalse(IntentGate.silentFor(expired, visible, nowMillis = 100L))
        val invisible = IntentGate.plan(expired, listOf(pairingA), request(), canAsk = false, nowMillis = 100L)
        assertEquals(IntentGate.RejectReason.PERMISSION_REQUIRES_CONSENT, assertIs<IntentGate.Plan.Reject>(invisible).reason)
    }

    @Test
    fun `forgotten approval on an already invisible window rejects instead of asking`() {
        val plan = IntentGate.plan(null, listOf(pairingA), request(), canAsk = false)
        assertEquals(IntentGate.RejectReason.PERMISSION_REQUIRES_CONSENT, assertIs<IntentGate.Plan.Reject>(plan).reason)
    }

    @Test
    fun `method and signing kind need scope before forwarding`() {
        val scoped = approvedForA.copy(scope = PermissionScope(setOf("sign_event"), setOf(1)))
        val signing = Nip55Request.SignEvent(null, null, """{"kind":1}""")
        assertIs<IntentGate.Plan.Forward>(IntentGate.plan(scoped, listOf(pairingA), signing, canAsk = true))
        val otherKind = signing.copy(eventJson = """{"kind":22242}""")
        assertIs<IntentGate.Plan.AskUser>(IntentGate.plan(scoped, listOf(pairingA), otherKind, canAsk = true))
        assertEquals(IntentGate.RejectReason.PERMISSION_REQUIRES_CONSENT,
            assertIs<IntentGate.Plan.Reject>(IntentGate.plan(scoped, listOf(pairingA), otherKind, canAsk = false)).reason)
        val decrypt = Nip55Request.Nip44Decrypt(null, null, pubkeyHexB, "cipher")
        assertIs<IntentGate.Plan.AskUser>(IntentGate.plan(scoped, listOf(pairingA), decrypt, canAsk = true))
        assertIs<IntentGate.Plan.Forward>(IntentGate.plan(scoped, listOf(pairingA), request(), canAsk = true))
    }

}

package dev.forgesworn.cambium.nip55

import dev.forgesworn.cambium.pairing.AppPermission
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.HeartwoodOutcome
import dev.forgesworn.cambium.signer.HeartwoodResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderGateTest {

    private val boundIdentity = "a1".repeat(32)

    // -- resolveCaller --

    @Test
    fun `an unresolvable calling package defers, without ever looking a permission up`() {
        val resolution = ProviderGate.resolveCaller(null) { error("must not be consulted") }
        assertIs<ProviderGate.Caller.Defer>(resolution)
    }

    @Test
    fun `a caller with no remembered choice defers to the intent`() {
        val resolution = ProviderGate.resolveCaller("com.example.client") { null }
        assertIs<ProviderGate.Caller.Defer>(resolution)
    }

    @Test
    fun `an approved caller passes through with its bound identity`() {
        val resolution = ProviderGate.resolveCaller("com.example.client") {
            AppPermission(AppPermissionState.APPROVED, boundIdentity)
        }
        val approved = assertIs<ProviderGate.Caller.Approved>(resolution)
        assertEquals("com.example.client", approved.packageName)
        assertEquals(boundIdentity, approved.boundIdentityPubkeyHex)
    }

    @Test
    fun `an approved caller with no binding still passes through`() {
        val resolution = ProviderGate.resolveCaller("com.example.client") {
            AppPermission(AppPermissionState.APPROVED, boundIdentityPubkeyHex = null)
        }
        assertNull(assertIs<ProviderGate.Caller.Approved>(resolution).boundIdentityPubkeyHex)
    }

    @Test
    fun `a remembered denial answers rejected`() {
        val resolution = ProviderGate.resolveCaller("com.example.client") {
            AppPermission(AppPermissionState.DENIED, boundIdentityPubkeyHex = null)
        }
        assertIs<ProviderGate.Caller.Rejected>(resolution)
    }

    // -- answerFor --

    @Test
    fun `no outcome at all defers to the intent`() {
        assertIs<ProviderGate.Answer.Defer>(ProviderGate.answerFor(null))
    }

    @Test
    fun `a fresh success answers its value`() {
        val answer = ProviderGate.answerFor(HeartwoodOutcome.Fresh(HeartwoodResult.Success("signed json")))
        assertEquals("signed json", assertIs<ProviderGate.Answer.Result>(answer).value)
    }

    @Test
    fun `a cached success answers its value the same way`() {
        val answer = ProviderGate.answerFor(HeartwoodOutcome.Cached(HeartwoodResult.Success("plaintext")))
        assertEquals("plaintext", assertIs<ProviderGate.Answer.Result>(answer).value)
    }

    @Test
    fun `a policy refusal is terminal`() {
        for (message in listOf("Unauthorized client", "request refused", "user denied", "not allowed")) {
            val answer = ProviderGate.answerFor(
                HeartwoodOutcome.Fresh(HeartwoodResult.Failure(HeartwoodError.Protocol(message)))
            )
            assertIs<ProviderGate.Answer.Rejected>(answer)
        }
    }

    @Test
    fun `a deterministic decrypt failure is terminal, cached or fresh`() {
        val error = HeartwoodError.Protocol("Decryption failed: bad ciphertext")
        assertIs<ProviderGate.Answer.Rejected>(ProviderGate.answerFor(HeartwoodOutcome.Fresh(HeartwoodResult.Failure(error))))
        assertIs<ProviderGate.Answer.Rejected>(ProviderGate.answerFor(HeartwoodOutcome.Cached(HeartwoodResult.Failure(error))))
    }

    @Test
    fun `transient failures defer to the intent rather than blocking the request`() {
        val transient = listOf(
            HeartwoodError.Timeout,
            HeartwoodError.NotConnected,
            HeartwoodError.InvalidInput("bad hex"),
            HeartwoodError.Protocol("relay unreachable"),
        )
        for (error in transient) {
            assertIs<ProviderGate.Answer.Defer>(
                ProviderGate.answerFor(HeartwoodOutcome.Fresh(HeartwoodResult.Failure(error)))
            )
        }
    }

    // -- isDeclinedDraft --

    @Test
    fun `a NIP-37 draft is declined`() {
        assertTrue(ProviderGate.isDeclinedDraft(31234))
    }

    @Test
    fun `ordinary kinds, and an unreadable kind, are not declined as drafts`() {
        assertFalse(ProviderGate.isDeclinedDraft(1))
        assertFalse(ProviderGate.isDeclinedDraft(0))
        assertFalse(ProviderGate.isDeclinedDraft(null))
    }
}

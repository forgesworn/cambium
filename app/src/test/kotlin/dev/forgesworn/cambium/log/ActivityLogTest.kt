package dev.forgesworn.cambium.log

import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.HeartwoodOutcome
import dev.forgesworn.cambium.signer.HeartwoodResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityLogTest {

    private fun entry(id: Int) = ActivityLogEntry(
        timestampMillis = id.toLong(),
        callingPackage = "org.example.app",
        method = "sign_event",
        eventKind = 1,
        identityLabel = "signer",
        outcome = ActivityLogEntry.Outcome.SIGNED,
    )

    @Test
    fun `appending under the cap just adds the entry`() {
        val existing = listOf(entry(1), entry(2))

        val updated = ActivityLog.append(existing, entry(3), maxEntries = 5)

        assertEquals(listOf(entry(1), entry(2), entry(3)), updated)
    }

    @Test
    fun `appending past the cap drops the oldest entries, keeping the newest`() {
        val existing = (1..5).map { entry(it) }

        val updated = ActivityLog.append(existing, entry(6), maxEntries = 5)

        assertEquals((2..6).map { entry(it) }, updated)
        assertEquals(5, updated.size)
    }

    @Test
    fun `the cap is never exceeded even by more than one entry's worth`() {
        val existing = (1..5).map { entry(it) }

        val updated = ActivityLog.append(existing, entry(6), maxEntries = 3)

        assertEquals((4..6).map { entry(it) }, updated)
    }

    @Test
    fun `a fresh success is SIGNED`() {
        val outcome = HeartwoodOutcome.Fresh(HeartwoodResult.Success("deadbeef"))

        assertEquals(ActivityLogEntry.Outcome.SIGNED, ActivityLog.outcomeFor(outcome))
    }

    @Test
    fun `a cached success is ANSWERED_FROM_CACHE, even though the underlying result is also Success`() {
        val outcome = HeartwoodOutcome.Cached(HeartwoodResult.Success("deadbeef"))

        assertEquals(ActivityLogEntry.Outcome.ANSWERED_FROM_CACHE, ActivityLog.outcomeFor(outcome))
    }

    @Test
    fun `a policy refusal failure is REJECTED_POLICY`() {
        val outcome = HeartwoodOutcome.Fresh(HeartwoodResult.Failure(HeartwoodError.Protocol("unauthorised")))

        assertEquals(ActivityLogEntry.Outcome.REJECTED_POLICY, ActivityLog.outcomeFor(outcome))
    }

    @Test
    fun `a non-policy failure is FAILED, whether fresh or cached`() {
        val fresh = HeartwoodOutcome.Fresh(HeartwoodResult.Failure(HeartwoodError.Timeout))
        val cachedDeterministic = HeartwoodOutcome.Cached(HeartwoodResult.Failure(HeartwoodError.Protocol("decryption failed")))

        assertEquals(ActivityLogEntry.Outcome.FAILED, ActivityLog.outcomeFor(fresh))
        assertEquals(ActivityLogEntry.Outcome.FAILED, ActivityLog.outcomeFor(cachedDeterministic))
    }
}

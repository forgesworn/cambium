package dev.forgesworn.cambium.log

import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.HeartwoodOutcome
import dev.forgesworn.cambium.signer.HeartwoodResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticReportTest {
    private fun entry(
        app: String = "org.example.app",
        identity: String = "My signer",
        outcome: ActivityLogEntry.Outcome = ActivityLogEntry.Outcome.SIGNED,
    ) = ActivityLogEntry(1000, app, "sign_event", 1, identity, outcome, durationMillis = 25)

    @Test
    fun `old persisted JSON loads with absent diagnostic fields`() {
        val entry = Json.decodeFromString<ActivityLogEntry>(
            """{"timestampMillis":1000,"callingPackage":"app","method":"sign_event","outcome":"SIGNED"}"""
        )
        assertNull(entry.failureCategory)
        assertNull(entry.durationMillis)
        assertNull(entry.identityReference)
    }

    @Test
    fun `filters compose and preserve input ordering`() {
        val first = entry()
        val otherApp = entry(app = "other")
        val otherIdentity = entry(identity = "Other signer")
        val failed = entry(outcome = ActivityLogEntry.Outcome.FAILED)
        val entries = listOf(first, otherApp, failed, otherIdentity)
        assertEquals(entries, ActivityLogFilter().apply(entries))
        assertEquals(listOf(first, failed), ActivityLogFilter("org.example.app", "My signer").apply(entries))
        assertEquals(listOf(failed), ActivityLogFilter("org.example.app", "My signer", ActivityLogEntry.Outcome.FAILED).apply(entries))
        assertTrue(ActivityLogFilter(callingPackage = "absent").apply(entries).isEmpty())
    }

    @Test
    fun `sharing replaces every free text field with fixed safe values or aliases`() {
        val secret = "bunker://secret?key=super-secret\n{\"content\":\"private plaintext\"}"
        val entry = entry(app = secret, identity = secret).copy(method = secret)
        val report = DiagnosticReport.format(listOf(entry), secret, 9999)
        assertFalse(report.contains(secret))
        assertFalse(report.contains("private plaintext"))
        assertFalse(report.contains("bunker://"))
        assertTrue(report.contains("app-1 | identity-1 | unknown"))
        assertTrue(report.contains("Cambium version: unknown"))
        assertTrue(report.contains("Android API: unknown"))
        assertTrue(report.contains("SIGNED | none | 25"))
    }

    @Test
    fun `aliases maintain grouping without revealing names`() {
        val report = DiagnosticReport.format(listOf(entry(), entry(app = "other"), entry(identity = "second")), "0.8.0", 35)
        assertTrue(report.contains("app-1 | identity-1"))
        assertTrue(report.contains("app-2 | identity-1"))
        assertTrue(report.contains("app-1 | identity-2"))
        assertFalse(report.contains("org.example.app"))
        assertFalse(report.contains("My signer"))
    }

    @Test
    fun `reports cap entries and represent historical missing metadata explicitly`() {
        val report = DiagnosticReport.format(List(501) { entry().copy(durationMillis = null) }, "0.8.0", 35)
        assertTrue(report.contains("Entries: 500"))
        assertEquals(500, report.lineSequence().count { it.startsWith("1000 |") })
        assertTrue(report.contains("SIGNED | none | unknown"))
    }

    @Test
    fun `classifier retains categories and no message`() {
        val cases = listOf(
            HeartwoodError.NotConnected to ActivityLogEntry.FailureCategory.NOT_CONNECTED,
            HeartwoodError.Busy to ActivityLogEntry.FailureCategory.BUSY,
            HeartwoodError.Timeout to ActivityLogEntry.FailureCategory.TIMEOUT,
            HeartwoodError.InvalidInput("secret input") to ActivityLogEntry.FailureCategory.INVALID_INPUT,
            HeartwoodError.Protocol("unauthorised secret") to ActivityLogEntry.FailureCategory.POLICY_REFUSAL,
            HeartwoodError.Protocol("decryption failed: secret") to ActivityLogEntry.FailureCategory.DECRYPTION_FAILED,
            HeartwoodError.Protocol("private plaintext") to ActivityLogEntry.FailureCategory.PROTOCOL,
        )
        for ((error, expected) in cases) {
            val diagnostics = ActivityLog.diagnosticsFor(HeartwoodOutcome.Fresh(HeartwoodResult.Failure(error)), -5)
            assertEquals(expected, diagnostics.failureCategory)
            assertEquals(0, diagnostics.durationMillis)
            assertFalse(diagnostics.toString().contains("secret"))
        }
        assertNull(ActivityLog.failureCategoryFor(HeartwoodOutcome.Fresh(HeartwoodResult.Success("private plaintext"))))
        assertEquals(ActivityLogEntry.FailureCategory.DECRYPTION_FAILED, ActivityLog.failureCategoryFor(
            HeartwoodOutcome.Cached(HeartwoodResult.Failure(HeartwoodError.Protocol("decryption failed")))
        ))
    }
}

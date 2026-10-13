package dev.forgesworn.cambium.log

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityLogIdentityTest {
    private fun entry(time: Long, label: String, reference: String? = null) = ActivityLogEntry(
        time, "org.example.app", "get_public_key", identityLabel = label,
        outcome = ActivityLogEntry.Outcome.SIGNED, identityReference = reference,
    )

    @Test
    fun `salted references are stable case insensitive and different per identity and install`() {
        val salt = ByteArray(32) { it.toByte() }
        val publicKey = "ab".repeat(32)
        val reference = IdentityReferences.create(salt, publicKey)
        assertEquals(reference, IdentityReferences.create(salt, publicKey.uppercase()))
        assertNotEquals(publicKey, reference)
        assertNotEquals(reference, IdentityReferences.create(salt, "cd".repeat(32)))
        assertNotEquals(reference, IdentityReferences.create(ByteArray(32) { 42 }, publicKey))
        assertEquals(64, reference!!.length)
        assertNull(IdentityReferences.create(salt, "bunker://secret"))
        assertNull(IdentityReferences.create(ByteArray(0), publicKey))
    }

    @Test
    fun `same labels stay separate and renamed labels preserve identity history`() {
        val firstOld = entry(1, "Old name", "private-reference-a")
        val firstNew = entry(3, "Shared name", "private-reference-a")
        val second = entry(2, "Shared name", "private-reference-b")
        val entries = listOf(second, firstNew, firstOld)
        val groups = ActivityLogIdentity.groupsFor(entries)
        assertEquals(2, groups.size)
        assertEquals(listOf("Shared name", "Shared name"), groups.map { it.label })
        assertTrue(groups.none { it.isLegacy })
        assertEquals(listOf(firstNew, firstOld), ActivityLogFilter(identityKey = ActivityLogIdentity.keyFor(firstNew)).apply(entries))
        assertEquals(listOf(second), ActivityLogFilter(identityKey = ActivityLogIdentity.keyFor(second)).apply(entries))
    }

    @Test
    fun `report aliases follow reference across renames and never expose local references`() {
        val entries = listOf(entry(1, "Old name", "private-reference-a"),
            entry(2, "Shared name", "private-reference-b"), entry(3, "Shared name", "private-reference-a"))
        val report = DiagnosticReport.format(entries, "0.8.0", 35)
        val rows = report.lineSequence().filter { it.contains(" | app-") }.toList()
        assertTrue(rows[0].contains("identity-1"))
        assertTrue(rows[1].contains("identity-2"))
        assertTrue(rows[2].contains("identity-1"))
        assertFalse(report.contains("private-reference"))
        assertFalse(report.contains("Shared name"))
        assertFalse(report.contains("Old name"))
    }

    @Test
    fun `legacy JSON and label grouping stay explicitly separate from modern identities`() {
        val legacy = Json.decodeFromString<ActivityLogEntry>(
            """{"timestampMillis":1,"callingPackage":"app","method":"get_public_key","identityLabel":"Shared name","outcome":"SIGNED"}"""
        )
        assertNull(legacy.identityReference)
        val modern = entry(2, "Shared name", "private-reference-a")
        val groups = ActivityLogIdentity.groupsFor(listOf(legacy, modern))
        assertEquals(2, groups.size)
        assertEquals(1, groups.count { it.isLegacy })
        assertEquals(listOf(legacy), ActivityLogFilter(identityKey = ActivityLogIdentity.keyFor(legacy)).apply(listOf(legacy, modern)))
        val report = DiagnosticReport.format(listOf(legacy, modern), "0.8.0", 35)
        assertTrue(report.contains("Older entries"))
        assertFalse(report.contains("private-reference-a"))
    }
}

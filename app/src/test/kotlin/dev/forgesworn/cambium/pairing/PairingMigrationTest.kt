package dev.forgesworn.cambium.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingMigrationTest {

    private val legacy = PairingMigration.Legacy(
        signerPubkeyHex = "a".repeat(64),
        relays = listOf("wss://relay.example"),
        secret = "s3cr3t",
        clientSecretKeyHex = "b".repeat(64),
        clientPublicKeyHex = "c".repeat(64),
        approvedPackages = setOf("org.approved.one", "org.approved.two"),
        deniedPackages = setOf("org.denied.one"),
    )

    @Test
    fun `the single legacy pairing becomes the one entry in the migrated list`() {
        val migrated = PairingMigration.migrate(legacy)

        assertEquals(1, migrated.pairings.size)
        val pairing = migrated.pairings.single()
        assertEquals(legacy.signerPubkeyHex, pairing.signerPubkeyHex)
        assertEquals(legacy.relays, pairing.relays)
        assertEquals(legacy.secret, pairing.secret)
        assertEquals(legacy.clientSecretKeyHex, pairing.clientSecretKeyHex)
        assertEquals(legacy.clientPublicKeyHex, pairing.clientPublicKeyHex)
        assertNull(pairing.label, "a migrated pairing gets no label -- display falls back to a truncated npub")
    }

    @Test
    fun `every previously-approved package migrates bound to the migrated identity`() {
        val migrated = PairingMigration.migrate(legacy)

        for (packageName in legacy.approvedPackages) {
            val permission = migrated.permissions.getValue(packageName)
            assertEquals(AppPermissionState.APPROVED, permission.state)
            assertEquals(legacy.signerPubkeyHex, permission.boundIdentityPubkeyHex)
        }
    }

    @Test
    fun `denied packages migrate with no identity binding at all`() {
        val migrated = PairingMigration.migrate(legacy)

        for (packageName in legacy.deniedPackages) {
            val permission = migrated.permissions.getValue(packageName)
            assertEquals(AppPermissionState.DENIED, permission.state)
            assertNull(permission.boundIdentityPubkeyHex)
        }
    }

    @Test
    fun `every approved and denied package is preserved, none dropped or duplicated`() {
        val migrated = PairingMigration.migrate(legacy)

        assertEquals(legacy.approvedPackages + legacy.deniedPackages, migrated.permissions.keys)
    }

    @Test
    fun `an install with no remembered apps at all migrates to just the one pairing`() {
        val noApps = legacy.copy(approvedPackages = emptySet(), deniedPackages = emptySet())

        val migrated = PairingMigration.migrate(noApps)

        assertEquals(1, migrated.pairings.size)
        assertEquals(emptyMap(), migrated.permissions)
    }
}

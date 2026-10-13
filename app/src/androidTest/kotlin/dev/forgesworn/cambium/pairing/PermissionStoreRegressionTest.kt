package dev.forgesworn.cambium.pairing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.cambium.nip55.IntentGate
import dev.forgesworn.cambium.nip55.Nip55Request
import dev.forgesworn.cambium.nip55.ProviderGate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore-backed storage on a disposable instrumentation install. Each test
 * redirects only PairingStore's preferences to a fresh fixture file, so an existing install's
 * pairings, app choices, client keypair and battery setting are never replaced or cleared. */
@RunWith(AndroidJUnit4::class)
class PermissionStoreRegressionTest {
    private val desk = "a".repeat(64)
    private val travel = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val app = "dev.cambium.permission.fixture.client"
    private val now = AtomicLong(1_700_000_000_000L)
    private lateinit var fixtureContext: FixtureContext
    private lateinit var store: PairingStore

    @Before
    fun prepareFreshFixture() {
        fixtureContext = FixtureContext(ApplicationProvider.getApplicationContext())
        store = newStore()
        assertTrue(store.pairings().isEmpty())
        assertTrue(store.allPermissions().isEmpty())
        store.addPairing(BunkerUri(desk, listOf("wss://fixture.example"), "fixture-secret"), "Desk")
        store.addPairing(BunkerUri(travel, listOf("wss://fixture.example"), "travel-secret"), "Travel")
    }

    @After
    fun removeOnlyFixtureStorage() {
        if (::fixtureContext.isInitialized) fixtureContext.deleteSharedPreferences("cambium_pairing")
    }

    private fun newStore() = PairingStore(fixtureContext, now::get)

    @Test
    fun timedApprovalExpiresAcrossIndependentStoreInstancesAndBothGates() {
        assertTrue(store.approve(app, desk, ApprovalLifetime.HOUR))
        val persisted = requireNotNull(newStore().allPermissions()[app])
        now.set(requireNotNull(persisted.expiresAtMillis) - 1)
        assertEquals(persisted, newStore().permission(app))
        now.incrementAndGet()
        assertNull(newStore().permission(app))
        assertEquals(persisted, newStore().allPermissions()[app])
        assertEquals(ProviderGate.Caller.Defer,
            ProviderGate.resolveCaller(app, nowMillis = now.get()) { persisted })
        val plan = IntentGate.plan(persisted, store.pairings(), Nip55Request.GetPublicKey(null, null),
            canAsk = false, nowMillis = now.get())
        assertEquals(IntentGate.Plan.Reject(IntentGate.RejectReason.PERMISSION_REQUIRES_CONSENT), plan)
    }

    @Test
    fun onceApprovalWritesNothingAndPreservesAnExistingRestrictedTimedGrant() {
        val otherApp = "$app.once"
        assertTrue(store.approve(otherApp, desk, ApprovalLifetime.ONCE))
        assertNull(newStore().permission(otherApp))
        assertFalse(newStore().allPermissions().containsKey(otherApp))
        val scope = PermissionScope(setOf("sign_event"), setOf(1))
        assertTrue(store.approve(app, desk, ApprovalLifetime.HOUR, scope))
        val earlier = requireNotNull(newStore().permission(app))
        assertTrue(newStore().approve(app, travel, ApprovalLifetime.ONCE))
        assertEquals(earlier, newStore().permission(app))
    }

    @Test
    fun restrictedApprovalPersistsOnlySelectedMethodsAndEventKinds() {
        val scope = PermissionScope(setOf("sign_event", "nip44_decrypt"), setOf(1, 7))
        assertTrue(store.approve(app, desk, ApprovalLifetime.DAY, scope))
        val permission = requireNotNull(newStore().permission(app))
        assertTrue(ProviderGate.allowsRequest(permission, "sign_event", 1, now.get()))
        assertTrue(ProviderGate.allowsRequest(permission, "nip44_decrypt", nowMillis = now.get()))
        assertFalse(ProviderGate.allowsRequest(permission, "sign_event", 22242, now.get()))
        assertFalse(ProviderGate.allowsRequest(permission, "sign_event", null, now.get()))
        assertFalse(ProviderGate.allowsRequest(permission, "nip44_encrypt", nowMillis = now.get()))
        assertTrue(ProviderGate.allowsRequest(permission, "get_public_key", nowMillis = now.get()))
    }

    @Test
    fun renameAndConfirmedRebindRetainCredentialsScopeAndExactExpiry() {
        val originalPairing = store.pairings().first { it.signerPubkeyHex == desk }
        val scope = PermissionScope(setOf("sign_event"), setOf(7))
        assertTrue(store.approve(app, desk, ApprovalLifetime.HOUR, scope))
        val originalPermission = requireNotNull(store.permission(app))
        now.addAndGet(15_000)
        assertTrue(newStore().renamePairing(desk, "  Office  "))
        assertEquals(originalPairing.copy(label = "Office"), newStore().pairings().first { it.signerPubkeyHex == desk })
        assertEquals(originalPermission, newStore().permission(app))
        assertTrue(newStore().rebindApproval(app, travel))
        assertEquals(originalPermission.copy(boundIdentityPubkeyHex = travel), newStore().permission(app))
        assertTrue(newStore().renamePairing(desk, "   "))
        assertNull(newStore().pairings().first { it.signerPubkeyHex == desk }.label)
        assertFalse(newStore().renamePairing("b".repeat(64), "Missing"))
    }

    @Test
    fun approvalEditsKeepTheirEndTimeUnlessUserExplicitlyChoosesANewLifetime() {
        assertTrue(store.approve(app, desk, ApprovalLifetime.HOUR))
        val originalEnd = requireNotNull(store.permission(app)).expiresAtMillis
        now.addAndGet(30_000)
        val scope = PermissionScope(setOf("nip44_decrypt"))
        assertTrue(newStore().updateApproval(app, desk, scope = scope))
        assertEquals(originalEnd, newStore().permission(app)?.expiresAtMillis)
        assertEquals(scope, newStore().permission(app)?.scope)
        assertTrue(newStore().updateApproval(app, desk, ApprovalLifetime.DAY, scope))
        assertEquals(now.get() + 86_400_000, newStore().permission(app)?.expiresAtMillis)
        assertFalse(newStore().updateApproval(app, desk, ApprovalLifetime.ONCE, scope))
        assertEquals(now.get() + 86_400_000, newStore().permission(app)?.expiresAtMillis)
    }

    @Test
    fun staleDeniedAndRemovedApprovalsCannotBeReboundOrReactivatedByAnEdit() {
        assertTrue(store.approve(app, desk, ApprovalLifetime.HOUR))
        val persisted = requireNotNull(store.permission(app))
        now.set(requireNotNull(persisted.expiresAtMillis))
        assertFalse(newStore().rebindApproval(app, travel))
        assertFalse(newStore().updateApproval(app, travel, ApprovalLifetime.ALWAYS))
        assertEquals(persisted, newStore().allPermissions()[app])
        store.deny(app)
        val denied = newStore().permission(app)
        assertFalse(newStore().rebindApproval(app, travel))
        assertFalse(newStore().updateApproval(app, travel, ApprovalLifetime.ALWAYS))
        assertEquals(denied, newStore().permission(app))
        assertTrue(store.approve(app, desk))
        store.removePairing(desk)
        assertNull(newStore().permission(app))
        assertFalse(newStore().rebindApproval(app, travel))
        assertFalse(newStore().updateApproval(app, travel, ApprovalLifetime.ALWAYS))
        assertFalse(newStore().approve(app, desk))
    }

    @Test
    fun authenticatedRelayChangePreservesRenameButCannotOverwriteNewPairingCredentials() {
        val old = store.pairings().first { it.signerPubkeyHex == desk }
        assertTrue(store.approve(app, desk, ApprovalLifetime.DAY))
        val permission = store.permission(app)
        assertTrue(newStore().renamePairing(desk, "Office"))
        val newRelays = listOf("wss://new.fixture.example")
        assertTrue(newStore().updatePairingRelays(old, newRelays))
        val updated = newStore().pairings().first { it.signerPubkeyHex == desk }
        assertEquals(old.copy(label = "Office", relays = newRelays), updated)
        assertEquals(permission, newStore().permission(app))
        // A new slot secret with the same relay list must still invalidate the old snapshot.
        store.addPairing(BunkerUri(desk, newRelays, "replacement-secret"))
        assertFalse(newStore().updatePairingRelays(updated, listOf("wss://stale.fixture.example")))
        val rePaired = newStore().pairings().first { it.signerPubkeyHex == desk }
        assertEquals("replacement-secret", rePaired.secret)
        assertEquals(newRelays, rePaired.relays)
        store.removePairing(desk)
        assertFalse(newStore().updatePairingRelays(rePaired, newRelays))
        assertTrue(newStore().pairings().none { it.signerPubkeyHex == desk })
    }

    @Test
    fun failedDurableRelayWriteRestoresOldInMemoryPairingAndReportsFailure() {
        val expected = store.pairings().first { it.signerPubkeyHex == desk }
        assertTrue(store.renamePairing(desk, "Office"))
        val before = store.pairings()
        fixtureContext.failNextCommit.set(true)
        assertFalse(store.updatePairingRelays(expected, listOf("wss://unpersisted.fixture.example")))
        assertFalse(fixtureContext.failNextCommit.get())
        assertEquals(before, store.pairings())
        assertEquals(before, newStore().pairings())
        // A subsequent actual durable write may succeed, retaining the benign rename.
        val relays = listOf("wss://durable.fixture.example")
        assertTrue(store.updatePairingRelays(expected, relays))
        assertEquals(expected.copy(label = "Office", relays = relays),
            newStore().pairings().first { it.signerPubkeyHex == desk })
    }

    @Test
    fun concurrentApprovalsFromSeparateStoreInstancesDoNotLoseOtherAppChoices() {
        val workers = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { worker ->
                workers.submit {
                    val separateStore = newStore()
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    for (entry in 0 until 8) {
                        val packageName = "$app.$worker.$entry"
                        if (entry % 2 == 0) check(separateStore.approve(packageName, desk, ApprovalLifetime.DAY))
                        else separateStore.deny(packageName)
                    }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
            val persisted = newStore().allPermissions()
            assertEquals(64, persisted.size)
            for (worker in 0 until 8) for (entry in 0 until 8) {
                val permission = requireNotNull(persisted["$app.$worker.$entry"])
                assertEquals(if (entry % 2 == 0) AppPermissionState.APPROVED else AppPermissionState.DENIED, permission.state)
                assertEquals(if (entry % 2 == 0) desk else null, permission.boundIdentityPubkeyHex)
            }
        } finally {
            start.countDown()
            workers.shutdownNow()
            assertTrue("Fixture worker threads must stop", workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun stalePairingSheetCannotWriteAGrantForAReplacementConnection() {
        val shown = store.pairings().first { it.signerPubkeyHex == desk }
        store.addPairing(BunkerUri(desk, shown.relays, "new-slot-secret"))
        assertFalse(newStore().approveIfPairingCurrent(app, shown, ApprovalLifetime.ALWAYS))
        assertFalse(newStore().allPermissions().containsKey(app))
        val replacement = store.pairings().first { it.signerPubkeyHex == desk }
        assertTrue(store.renamePairing(desk, "Office"))
        assertTrue(newStore().approveIfPairingCurrent(app, replacement, ApprovalLifetime.HOUR,
            PermissionScope(setOf("nip44_decrypt"))))
        val approved = store.permission(app)
        store.removePairing(desk)
        assertFalse(newStore().approveIfPairingCurrent(app, replacement, ApprovalLifetime.ALWAYS))
        assertNull(newStore().permission(app))
        assertEquals(desk, approved?.boundIdentityPubkeyHex)
    }

    @Test
    fun durationAndScopeConfirmationCannotOverwriteANewerChoice() {
        val scope = PermissionScope(setOf("sign_event"), setOf(1))
        assertTrue(store.approve(app, desk, ApprovalLifetime.HOUR, scope))
        val shown = requireNotNull(store.permission(app))
        assertTrue(newStore().rebindApproval(app, travel))
        val rebound = requireNotNull(store.permission(app))
        assertFalse(store.rebindApproval(app, desk, expected = shown))
        assertEquals(rebound, newStore().permission(app))
        assertFalse(store.changeApprovalLifetimeIfCurrent(app, shown, ApprovalLifetime.ALWAYS))
        assertFalse(store.updateApproval(app, travel, scope = null, expected = shown))
        assertEquals(rebound, newStore().permission(app))
        val narrowedScope = PermissionScope(setOf("nip44_decrypt"))
        assertTrue(store.updateApproval(app, travel, scope = narrowedScope, expected = rebound))
        val narrowed = requireNotNull(newStore().permission(app))
        assertFalse(store.rebindApproval(app, desk, expected = rebound))
        assertEquals(narrowed, newStore().permission(app))
        assertTrue(store.rebindApproval(app, desk, expected = narrowed))
        assertEquals(narrowed.copy(boundIdentityPubkeyHex = desk), newStore().permission(app))
        store.deny(app)
        val denied = requireNotNull(newStore().permission(app))
        assertFalse(store.changeApprovalLifetimeIfCurrent(app, rebound, ApprovalLifetime.DAY))
        assertFalse(store.changeApprovalLifetimeIfCurrent(app, denied, ApprovalLifetime.ALWAYS))
        assertEquals(denied, newStore().permission(app))
        assertTrue(store.approve(app, desk, ApprovalLifetime.HOUR, scope))
        val expiring = requireNotNull(store.permission(app))
        now.set(requireNotNull(expiring.expiresAtMillis))
        assertNull(store.permission(app))
        assertTrue(newStore().changeApprovalLifetimeIfCurrent(app, expiring, ApprovalLifetime.DAY))
        val renewed = requireNotNull(store.permission(app))
        assertEquals(scope, renewed.scope)
        assertEquals(desk, renewed.boundIdentityPubkeyHex)
        assertEquals(now.get() + 86_400_000, renewed.expiresAtMillis)
        assertTrue(newStore().changeApprovalLifetimeIfCurrent(app, renewed, ApprovalLifetime.ONCE))
        assertNull(newStore().permission(app))
        assertFalse(newStore().allPermissions().containsKey(app))
    }

    @Test
    fun staleApprovalSheetCannotOverrideANewerDenialScopeOrBindingEvenForOnce() {
        val pairing = store.pairings().first { it.signerPubkeyHex == desk }
        // First contact captured no active choice. A newer permanent denial must win.
        store.deny(app)
        val denied = newStore().permission(app)
        assertFalse(store.approveIfSheetCurrent(app, pairing, null, ApprovalLifetime.ONCE))
        assertFalse(store.approveIfSheetCurrent(app, pairing, null, ApprovalLifetime.ALWAYS))
        assertFalse(store.approveIfSheetCurrent(app, pairing, denied, ApprovalLifetime.ONCE))
        assertEquals(denied, newStore().permission(app))
        store.forget(app)
        val initialScope = PermissionScope(setOf("sign_event"), setOf(1, 7))
        assertTrue(store.approveIfSheetCurrent(app, pairing, null, ApprovalLifetime.HOUR, initialScope))
        val shown = requireNotNull(newStore().permission(app))
        val narrowedScope = PermissionScope(setOf("sign_event"), setOf(1))
        assertTrue(store.updateApproval(app, desk, scope = narrowedScope, expected = shown))
        val narrowed = requireNotNull(store.permission(app))
        assertFalse(newStore().approveIfSheetCurrent(app, pairing, shown, ApprovalLifetime.ALWAYS))
        assertFalse(newStore().approveIfSheetCurrent(app, pairing, shown, ApprovalLifetime.ONCE))
        assertEquals(narrowed, newStore().permission(app))
        assertTrue(newStore().approveIfSheetCurrent(app, pairing, narrowed, ApprovalLifetime.ONCE))
        assertEquals(narrowed, newStore().permission(app))
        assertTrue(store.rebindApproval(app, travel, expected = narrowed))
        val rebound = requireNotNull(store.permission(app))
        assertFalse(newStore().approveIfSheetCurrent(app, pairing, narrowed, ApprovalLifetime.ONCE))
        assertEquals(rebound, newStore().permission(app))
    }

    /** EncryptedSharedPreferences' keyset readers use applicationContext too. Redirect both
     * access paths to the same unique file while preserving all Android encryption behaviour. */
    private class FixtureContext(context: Context) : ContextWrapper(context) {
        private val fixtureName = "cambium_permission_fixture_${UUID.randomUUID()}"
        val failNextCommit = AtomicBoolean(false)

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = super.getSharedPreferences(if (name == "cambium_pairing") fixtureName else name, mode)
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun commit(): Boolean {
                            if (!failNextCommit.compareAndSet(true, false)) return editor.commit()
                            // Android publishes SharedPreferences memory before the disk write
                            // can fail. Simulate that ordering through the real preference store.
                            editor.apply()
                            return false
                        }
                    }
                }
            }
        }

        override fun deleteSharedPreferences(name: String): Boolean =
            super.deleteSharedPreferences(if (name == "cambium_pairing") fixtureName else name)
    }
}

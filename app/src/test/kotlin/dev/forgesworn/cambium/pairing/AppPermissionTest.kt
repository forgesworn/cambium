package dev.forgesworn.cambium.pairing

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppPermissionTest {
    private val identity = "a1".repeat(32)
    private val now = 1_700_000_000_000L

    @Test
    fun `old approvals decode as indefinite whole app consent`() {
        val permission = Json.decodeFromString<AppPermission>(
            """{"state":"APPROVED","boundIdentityPubkeyHex":"$identity"}"""
        )
        assertNull(permission.scope)
        assertNull(permission.expiresAtMillis)
        assertTrue(permission.allows("nip44_decrypt", nowMillis = Long.MAX_VALUE))
        assertTrue(permission.allows("sign_event", 22242, nowMillis = now))
    }

    @Test
    fun `once produces no persisted permission`() {
        assertNull(ApprovalLifetime.ONCE.permission(identity, nowMillis = now))
    }

    @Test
    fun `hour and day expire precisely at their end time`() {
        for ((lifetime, duration) in listOf(ApprovalLifetime.HOUR to 3_600_000L, ApprovalLifetime.DAY to 86_400_000L)) {
            val permission = requireNotNull(lifetime.permission(identity, nowMillis = now))
            assertEquals(now + duration, permission.expiresAtMillis)
            assertTrue(permission.isActive(now + duration - 1))
            assertFalse(permission.isActive(now + duration))
            assertFalse(permission.allows("get_public_key", nowMillis = now + duration))
        }
    }

    @Test
    fun `clock rollback before timed grant fails closed`() {
        val permission = requireNotNull(ApprovalLifetime.HOUR.permission(identity, nowMillis = now))
        assertFalse(permission.isActive(now - 1))
        assertTrue(permission.isActive(now))
    }

    @Test
    fun `always has no time limit`() {
        val permission = requireNotNull(ApprovalLifetime.ALWAYS.permission(identity, nowMillis = now))
        assertNull(permission.expiresAtMillis)
        assertNull(permission.grantedAtMillis)
        assertTrue(permission.isActive(Long.MAX_VALUE))
    }

    @Test
    fun `scope never permits omitted methods`() {
        val scope = PermissionScope(setOf("nip44_decrypt"))
        val permission = requireNotNull(ApprovalLifetime.HOUR.permission(identity, scope, now))
        assertTrue(permission.allows("nip44_decrypt", nowMillis = now))
        assertFalse(permission.allows("nip44_encrypt", nowMillis = now))
        assertFalse(permission.allows("sign_event", 1, now))
        assertTrue(permission.allows("get_public_key", nowMillis = now))
    }

    @Test
    fun `event restrictions never match absent or unreadable kind`() {
        val scope = PermissionScope(setOf("sign_event", "nip04_encrypt"), setOf(1, 7))
        assertTrue(scope.allows("sign_event", 1))
        assertTrue(scope.allows("sign_event", 7))
        assertFalse(scope.allows("sign_event", 22242))
        assertFalse(scope.allows("sign_event", null))
        assertTrue(scope.allows("nip04_encrypt"))
        assertFalse(PermissionScope(setOf("sign_event"), emptySet()).allows("sign_event", 1))
    }

    @Test
    fun `denial is identity agnostic and grants no methods`() {
        val denial = AppPermission(AppPermissionState.DENIED, null)
        assertTrue(denial.isActive(Long.MAX_VALUE))
        assertFalse(denial.allows("get_public_key", nowMillis = now))
        assertFalse(denial.allows("sign_event", 1, now))
    }

    @Test
    fun `expiry and restricted scope survive encrypted blob serialisation`() {
        val original = requireNotNull(ApprovalLifetime.DAY.permission(identity, PermissionScope(setOf("sign_event"), setOf(1)), now))
        assertEquals(original, Json.decodeFromString<AppPermission>(Json.encodeToString(original)))
    }

}

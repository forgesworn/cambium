package dev.forgesworn.cambium.nip55

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestPermissionScopeTest {
    @Test
    fun `one operation signing consent retains its kind and does not allow other methods`() {
        val request = Nip55Request.SignEvent(null, null, """{"kind":1,"content":"hello"}""")
        val scope = request.permissionScope()
        assertTrue(scope.allows("sign_event", 1))
        assertFalse(scope.allows("sign_event", 22242))
        assertFalse(scope.allows("nip44_decrypt"))
    }

    @Test
    fun `unreadable kind grants no future signing kind`() {
        for (payload in listOf("broken", "{}", """{"kind":[]}""")) {
            val scope = Nip55Request.SignEvent(null, null, payload).permissionScope()
            assertFalse(scope.allows("sign_event", null))
            assertFalse(scope.allows("sign_event", 1))
        }
    }

    @Test
    fun `get public key requested permissions are display only`() {
        val request = Nip55Request.GetPublicKey(null, null, listOf(RequestedPermission("sign_event", 1)))
        assertEquals(setOf("get_public_key"), request.permissionScope().methods)
        assertFalse(request.permissionScope().allows("sign_event", 1))
    }

    @Test
    fun `all supported crypto transports map to their exact own method`() {
        val requests = listOf(
            Nip55Request.Nip04Encrypt(null, null, "key", "plain") to "nip04_encrypt",
            Nip55Request.Nip04Decrypt(null, null, "key", "cipher") to "nip04_decrypt",
            Nip55Request.Nip44Encrypt(null, null, "key", "plain") to "nip44_encrypt",
            Nip55Request.Nip44Decrypt(null, null, "key", "cipher") to "nip44_decrypt",
            Nip55Request.DecryptZapEvent(null, null, "{}") to "decrypt_zap_event",
        )
        for ((request, method) in requests) assertEquals(setOf(method), request.permissionScope().methods)
    }
}

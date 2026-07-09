package dev.forgesworn.cambium.nip55

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class Nip55RequestTest {

    private fun raw(
        payload: String? = null,
        type: String? = null,
        id: String? = "req-1",
        currentUser: String? = "abc123",
        pubkey: String? = null,
        permissions: String? = null,
    ) = RawSignerIntent(
        payload = payload,
        type = type,
        id = id,
        currentUser = currentUser,
        pubkey = pubkey,
        permissions = permissions,
    )

    @Test
    fun `parses get_public_key with no payload required`() {
        val request = Nip55Request.from(raw(type = "get_public_key"))

        val parsed = assertIs<Nip55Request.GetPublicKey>(request)
        assertEquals("req-1", parsed.id)
        assertEquals("abc123", parsed.currentUser)
        assertEquals(emptyList(), parsed.permissions)
    }

    @Test
    fun `parses get_public_key's permissions extra when present`() {
        val permissionsJson = """[{"type":"sign_event","kind":1},{"type":"nip44_decrypt"}]"""
        val request = Nip55Request.from(raw(type = "get_public_key", permissions = permissionsJson))

        val parsed = assertIs<Nip55Request.GetPublicKey>(request)
        assertEquals(
            listOf(RequestedPermission("sign_event", 1), RequestedPermission("nip44_decrypt", null)),
            parsed.permissions,
        )
    }

    @Test
    fun `parses sign_event with the event json as payload`() {
        val eventJson = """{"kind":1,"content":"gm"}"""
        val request = Nip55Request.from(raw(type = "sign_event", payload = eventJson))

        val parsed = assertIs<Nip55Request.SignEvent>(request)
        assertEquals(eventJson, parsed.eventJson)
    }

    @Test
    fun `sign_event without a payload is rejected`() {
        assertNull(Nip55Request.from(raw(type = "sign_event", payload = null)))
        assertNull(Nip55Request.from(raw(type = "sign_event", payload = "  ")))
    }

    @Test
    fun `parses nip04_encrypt with pubkey and payload`() {
        val request = Nip55Request.from(
            raw(type = "nip04_encrypt", payload = "hello", pubkey = "deadbeef")
        )

        val parsed = assertIs<Nip55Request.Nip04Encrypt>(request)
        assertEquals("deadbeef", parsed.pubkeyHex)
        assertEquals("hello", parsed.plaintext)
    }

    @Test
    fun `nip04_decrypt without a pubkey is rejected`() {
        assertNull(Nip55Request.from(raw(type = "nip04_decrypt", payload = "cipher", pubkey = null)))
    }

    @Test
    fun `parses nip44_encrypt and nip44_decrypt`() {
        val encrypt = assertIs<Nip55Request.Nip44Encrypt>(
            Nip55Request.from(raw(type = "nip44_encrypt", payload = "hi", pubkey = "cafe"))
        )
        assertEquals("hi", encrypt.plaintext)

        val decrypt = assertIs<Nip55Request.Nip44Decrypt>(
            Nip55Request.from(raw(type = "nip44_decrypt", payload = "cT==", pubkey = "cafe"))
        )
        assertEquals("cT==", decrypt.ciphertext)
    }

    @Test
    fun `is case-insensitive on the type extra`() {
        val request = Nip55Request.from(raw(type = "SIGN_EVENT", payload = """{"kind":1}"""))
        assertIs<Nip55Request.SignEvent>(request)
    }

    @Test
    fun `unknown method type returns null`() {
        assertNull(Nip55Request.from(raw(type = "some_future_method", payload = "x")))
    }

    @Test
    fun `parses decrypt_zap_event with the zap request json as payload and no pubkey needed`() {
        val eventJson = """{"kind":9734,"pubkey":"abc","tags":[["anon","x"]]}"""
        val request = Nip55Request.from(raw(type = "decrypt_zap_event", payload = eventJson, pubkey = null))

        val parsed = assertIs<Nip55Request.DecryptZapEvent>(request)
        assertEquals(eventJson, parsed.eventJson)
    }

    @Test
    fun `decrypt_zap_event without a payload is rejected`() {
        assertNull(Nip55Request.from(raw(type = "decrypt_zap_event", payload = null)))
        assertNull(Nip55Request.from(raw(type = "decrypt_zap_event", payload = "  ")))
    }

    @Test
    fun `missing type returns null`() {
        assertNull(Nip55Request.from(raw(type = null)))
    }

    @Test
    fun `id and current_user are echoed through untouched`() {
        val request = Nip55Request.from(raw(type = "get_public_key", id = "xyz", currentUser = "user-1"))
        assertEquals("xyz", request?.id)
        assertEquals("user-1", request?.currentUser)
    }
}

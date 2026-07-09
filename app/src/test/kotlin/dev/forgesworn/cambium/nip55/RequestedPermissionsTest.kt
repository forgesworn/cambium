package dev.forgesworn.cambium.nip55

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestedPermissionsTest {

    @Test
    fun `parses a mixed list with and without a kind`() {
        val json = """[{"type":"sign_event","kind":22242},{"type":"nip44_decrypt"}]"""
        val parsed = parseRequestedPermissions(json)

        assertEquals(listOf(RequestedPermission("sign_event", 22242), RequestedPermission("nip44_decrypt", null)), parsed)
    }

    @Test
    fun `null input is an empty list`() {
        assertTrue(parseRequestedPermissions(null).isEmpty())
    }

    @Test
    fun `blank input is an empty list`() {
        assertTrue(parseRequestedPermissions("   ").isEmpty())
    }

    @Test
    fun `invalid json is an empty list, not an exception`() {
        assertTrue(parseRequestedPermissions("not json").isEmpty())
    }

    @Test
    fun `entries without a type are dropped rather than failing the whole list`() {
        val json = """[{"type":"sign_event"},{"kind":1},{"type":"nip04_encrypt"}]"""
        val parsed = parseRequestedPermissions(json)

        assertEquals(listOf(RequestedPermission("sign_event", null), RequestedPermission("nip04_encrypt", null)), parsed)
    }

    @Test
    fun `an empty array is an empty list`() {
        assertTrue(parseRequestedPermissions("[]").isEmpty())
    }

    @Test
    fun `summarises with and without kinds`() {
        val summary = summariseRequestedPermissions(
            listOf(RequestedPermission("sign_event", 1), RequestedPermission("nip44_decrypt", null)),
        )
        assertEquals("sign_event (kind 1), nip44_decrypt", summary)
    }

    @Test
    fun `summary of an empty list is empty`() {
        assertEquals("", summariseRequestedPermissions(emptyList()))
    }
}

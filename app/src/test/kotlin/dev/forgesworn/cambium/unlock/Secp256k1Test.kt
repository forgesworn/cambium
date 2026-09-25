package dev.forgesworn.cambium.unlock

import dev.forgesworn.cambium.toHex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Secp256k1Test {

    private fun secret(hex: String): ByteArray = hex.hexToBytesOrNull()!!

    @Test
    fun `the public key for secret 1 is the generator point itself`() {
        assertEquals(
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            Secp256k1.publicKeyXOnly(secret("00".repeat(31) + "01")).toHex(),
        )
    }

    /** A well-known secp256k1 constant: the x-coordinate of 2G. */
    @Test
    fun `the public key for secret 2 is 2G`() {
        assertEquals(
            "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5",
            Secp256k1.publicKeyXOnly(secret("00".repeat(31) + "02")).toHex(),
        )
    }

    @Test
    fun `x-only ECDH is symmetric regardless of either point's y parity`() {
        val a = secret("11".repeat(32))
        val b = secret("22".repeat(32))
        val pubA = Secp256k1.publicKeyXOnly(a)
        val pubB = Secp256k1.publicKeyXOnly(b)
        val sharedFromA = Secp256k1.ecdhXOnly(a, pubB)
        val sharedFromB = Secp256k1.ecdhXOnly(b, pubA)
        assertNotNull(sharedFromA)
        assertEquals(sharedFromA.toHex(), sharedFromB?.toHex())
    }
}

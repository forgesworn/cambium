package dev.forgesworn.cambium.unlock

import java.math.BigInteger

/**
 * **Test-only.** Just enough secp256k1 (affine point arithmetic over [P], double-and-add scalar
 * multiplication) to compute a BIP-340 x-only public key and an x-only ECDH shared point for
 * [Nip44]. This is not production code: it is naive, non-constant-time affine arithmetic (a
 * modular inverse per addition), acceptable only because it exists purely so
 * `EnrolInviteVectorTest` can check Sapwood's wire format (NIP-44 v2 content, tags) byte for byte
 * on the host JVM, where rust-nostr's actual, constant-time implementation cannot load at all
 * (native code per ABI -- see `signer/HeartwoodClient.kt`'s class doc). Production
 * (`signer/UnlockRelay.kt`'s `UnlockNostr.inviteReplyEvent`) uses rust-nostr's `Keys`/
 * `nip44Encrypt` throughout, never this file.
 */
internal object Secp256k1 {
    val P: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX: BigInteger = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY: BigInteger = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val G = Point(GX, GY)

    data class Point(val x: BigInteger, val y: BigInteger)

    /** The x-only public key (32 bytes big-endian) for a raw 32-byte secret key scalar. */
    fun publicKeyXOnly(secretKey: ByteArray): ByteArray {
        val d = BigInteger(1, secretKey).mod(N)
        val point = multiply(d, G) ?: throw IllegalArgumentException("secret key produced the point at infinity")
        return to32Bytes(point.x)
    }

    /**
     * The x coordinate (32 bytes big-endian) of `secretKey * liftX(pubkeyXOnly)`, i.e. NIP-44's
     * `secp256k1_ecdh`: the recipient's x-only key is lifted with the even-y point BIP-340 always
     * uses, then multiplied by the sender's raw scalar. Null if [pubkeyXOnly] is not a valid
     * x-coordinate on the curve.
     */
    fun ecdhXOnly(secretKey: ByteArray, pubkeyXOnly: ByteArray): ByteArray? {
        val recipient = liftX(pubkeyXOnly) ?: return null
        val d = BigInteger(1, secretKey).mod(N)
        val shared = multiply(d, recipient) ?: return null
        return to32Bytes(shared.x)
    }

    /** BIP-340 `lift_x`: the point on the curve with this x-coordinate and an even y, or null. */
    private fun liftX(xBytes: ByteArray): Point? {
        val x = BigInteger(1, xBytes)
        if (x >= P) return null
        val ySquared = x.modPow(BigInteger.valueOf(3), P).add(BigInteger.valueOf(7)).mod(P)
        var y = ySquared.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(BigInteger.valueOf(2), P) != ySquared) return null // x is not a valid coordinate
        if (y.testBit(0)) y = P.subtract(y)
        return Point(x, y)
    }

    private fun multiply(scalar: BigInteger, point: Point): Point? {
        var result: Point? = null
        var addend = point
        var k = scalar
        while (k.signum() > 0) {
            if (k.testBit(0)) result = add(result, addend)
            addend = double(addend)
            k = k.shiftRight(1)
        }
        return result
    }

    private fun add(p1: Point?, p2: Point): Point {
        if (p1 == null) return p2
        if (p1.x == p2.x) {
            return if (p1.y.add(p2.y).mod(P) == BigInteger.ZERO) {
                throw IllegalArgumentException("point at infinity is not representable here")
            } else {
                double(p1)
            }
        }
        val lambda = p2.y.subtract(p1.y).mod(P).multiply(p2.x.subtract(p1.x).mod(P).modInverse(P)).mod(P)
        val x3 = lambda.modPow(BigInteger.valueOf(2), P).subtract(p1.x).subtract(p2.x).mod(P)
        val y3 = lambda.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P)
        return Point(x3, y3)
    }

    private fun double(p: Point): Point {
        val lambda = p.x.modPow(BigInteger.valueOf(2), P).multiply(BigInteger.valueOf(3))
            .multiply(p.y.shiftLeft(1).mod(P).modInverse(P)).mod(P)
        val x3 = lambda.modPow(BigInteger.valueOf(2), P).subtract(p.x.shiftLeft(1)).mod(P)
        val y3 = lambda.multiply(p.x.subtract(x3)).subtract(p.y).mod(P)
        return Point(x3, y3)
    }

    private fun to32Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        val copyLen = minOf(raw.size, 32)
        raw.copyInto(out, 32 - copyLen, raw.size - copyLen, raw.size)
        return out
    }
}

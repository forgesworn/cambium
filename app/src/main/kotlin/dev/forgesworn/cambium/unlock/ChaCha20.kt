package dev.forgesworn.cambium.unlock

/**
 * IETF ChaCha20 (RFC 8439): 256-bit key, 96-bit nonce, 32-bit block counter from 0. Pure Kotlin
 * rather than `Cipher.getInstance("ChaCha20")`: Conscrypt only offers the raw stream cipher from
 * API 28 and Cambium's floor is 27, and this way the JVM tests run the exact code the phone runs.
 * Only ever used to open a lock message of at most [PhoneUnlock.MAX_CONTENT_LEN] bytes, so speed
 * is irrelevant.
 */
internal object ChaCha20 {

    fun xor(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = le32(key, i * 4)
        state[12] = 0
        for (i in 0 until 3) state[13 + i] = le32(nonce, i * 4)

        val out = ByteArray(input.size)
        val working = IntArray(16)
        val block = ByteArray(64)
        var offset = 0
        while (offset < input.size) {
            state.copyInto(working)
            repeat(10) {
                quarter(working, 0, 4, 8, 12)
                quarter(working, 1, 5, 9, 13)
                quarter(working, 2, 6, 10, 14)
                quarter(working, 3, 7, 11, 15)
                quarter(working, 0, 5, 10, 15)
                quarter(working, 1, 6, 11, 12)
                quarter(working, 2, 7, 8, 13)
                quarter(working, 3, 4, 9, 14)
            }
            for (i in 0 until 16) {
                val v = working[i] + state[i]
                block[i * 4] = v.toByte()
                block[i * 4 + 1] = (v ushr 8).toByte()
                block[i * 4 + 2] = (v ushr 16).toByte()
                block[i * 4 + 3] = (v ushr 24).toByte()
            }
            val n = minOf(64, input.size - offset)
            for (i in 0 until n) out[offset + i] = (input[offset + i].toInt() xor block[i].toInt()).toByte()
            offset += n
            state[12] += 1
        }
        block.fill(0)
        working.fill(0)
        state.fill(0)
        return out
    }

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or
            ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or
            ((b[i + 3].toInt() and 0xFF) shl 24)
}

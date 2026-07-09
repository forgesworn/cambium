package dev.forgesworn.cambium.nip57

/**
 * BIP-173 bech32 (the original variant, not bech32m -- Nostr's own npub/nsec/note encoding uses
 * the same original variant, and DIP-03's private-zap encoding, the only consumer of this file,
 * does not call out bech32m either).
 *
 * Deliberately does NOT enforce BIP-173's 90-character total-length recommendation: that limit
 * exists for human-readability/QR-size reasons in the Bitcoin-address context it was designed
 * for. DIP-03 (https://github.com/damus-io/dips/blob/master/03.md) reuses bech32 purely as a
 * generic large-payload encoding for zap ciphertext, which routinely exceeds it -- the `anon` tag
 * example in that spec is itself well over 90 characters.
 *
 * Pure Kotlin, no Android: JVM-testable like the rest of Cambium's parsers.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /**
     * Decodes [input] and requires its human-readable part to equal [expectedHrp] exactly.
     * Returns the raw decoded bytes (after unpacking bech32's 5-bit groups back into 8-bit bytes)
     * on success.
     */
    fun decode(input: String, expectedHrp: String): Result<ByteArray> = runCatching {
        require(input == input.lowercase() || input == input.uppercase()) { "mixed-case bech32 string" }
        val normalized = input.lowercase()

        val separator = normalized.lastIndexOf('1')
        require(separator >= 1) { "no '1' separator found" }
        require(normalized.length - separator - 1 >= 6) { "data part shorter than the 6-character checksum" }

        val hrp = normalized.substring(0, separator)
        require(hrp == expectedHrp) { "expected hrp '$expectedHrp', got '$hrp'" }

        val dataChars = normalized.substring(separator + 1)
        val values = IntArray(dataChars.length) { i ->
            val index = CHARSET.indexOf(dataChars[i])
            require(index >= 0) { "invalid bech32 character '${dataChars[i]}'" }
            index
        }

        require(verifyChecksum(hrp, values)) { "invalid checksum" }

        val payload = values.copyOfRange(0, values.size - 6)
        requireNotNull(convertBits(payload, fromBits = 5, toBits = 8, pad = false)) {
            "data part does not repack cleanly into whole bytes"
        }
    }

    /** Encodes [data] under [hrp], for round-trip testing against [decode]. */
    fun encode(hrp: String, data: ByteArray): String {
        val packedBytes = data.map { it.toInt() and 0xff }.toIntArray()
        val fiveBitValues = requireNotNull(convertBits(packedBytes, fromBits = 8, toBits = 5, pad = true))
            .map { it.toInt() and 0xff }
            .toIntArray()
        val checksum = createChecksum(hrp, fiveBitValues)
        val dataPart = (fiveBitValues + checksum).map { CHARSET[it] }.joinToString("")
        return "$hrp$SEPARATOR$dataPart"
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor v
            for (i in 0 until 5) {
                if ((top ushr i) and 1 == 1) {
                    chk = chk xor GENERATOR[i]
                }
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val high = hrp.map { it.code shr 5 }
        val low = hrp.map { it.code and 31 }
        return (high + listOf(0) + low).toIntArray()
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean =
        polymod(hrpExpand(hrp) + data) == 1

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor 1
        return IntArray(6) { i -> (mod ushr (5 * (5 - i))) and 31 }
    }

    /**
     * Repacks groups of [fromBits]-wide values into groups of [toBits]-wide values (e.g. 8-bit
     * bytes -> 5-bit bech32 groups, or back). Faithful to the reference BIP-173 algorithm,
     * including masking the accumulator to [fromBits] + [toBits] - 1 bits on every step -- without
     * that mask, `acc` grows without bound for long inputs (exactly what this decoder is for) and
     * silently produces wrong output once it exceeds 32 bits instead of failing loudly.
     *
     * The output length is exactly computable up front from [data]'s size, so this writes
     * directly into a pre-sized `ByteArray` by index rather than boxing each byte into a
     * `MutableList<Byte>` -- this runs on the binder thread inside `decrypt_zap_event` query
     * bursts, where the boxing/unboxing and list growth are avoidable allocation churn.
     */
    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray? {
        var acc = 0
        var bits = 0
        val maxV = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1

        val totalBits = data.size * fromBits
        val outLen = totalBits / toBits + if (pad && totalBits % toBits != 0) 1 else 0
        val out = ByteArray(outLen)
        var outIndex = 0

        for (value in data) {
            if (value < 0 || (value shr fromBits) != 0) return null
            acc = ((acc shl fromBits) or value) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out[outIndex++] = ((acc shr bits) and maxV).toByte()
            }
        }
        if (pad) {
            if (bits > 0) out[outIndex++] = ((acc shl (toBits - bits)) and maxV).toByte()
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxV) != 0) {
            return null
        }
        return out
    }

    private const val SEPARATOR = "1"
}

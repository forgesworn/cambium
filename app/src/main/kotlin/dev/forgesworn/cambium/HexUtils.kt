package dev.forgesworn.cambium

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * Lowercase hex encoding, shared by anywhere that needs to turn raw bytes into a hex string
 * (`IdentityRouting`'s `current_user` normalisation, `DecryptCache`'s cache key -- both on hot
 * paths, a burst of silent-path queries in the first case, every cache lookup in the second)
 * rather than each keeping its own copy of the same formatting. A lookup table and direct
 * `CharArray` writes, not `"%02x".format(...)`: `String.format`/`Formatter` parses the format
 * string and boxes every byte on each call, which is real, avoidable overhead on a path called
 * this often. Pure Kotlin, no Android.
 */
fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        chars[i * 2] = HEX_DIGITS[value ushr 4]
        chars[i * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return String(chars)
}

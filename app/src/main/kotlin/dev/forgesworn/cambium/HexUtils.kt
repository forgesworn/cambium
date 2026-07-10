package dev.forgesworn.cambium

/** Lowercase hex encoding, shared by anywhere that needs to turn raw bytes into a hex string
 * (`IdentityRouting`'s `current_user` normalisation, `DecryptCache`'s cache key) rather than each
 * keeping its own copy of the same `"%02x"` formatting. Pure Kotlin, no Android. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

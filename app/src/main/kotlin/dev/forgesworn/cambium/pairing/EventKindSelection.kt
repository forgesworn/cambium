package dev.forgesworn.cambium.pairing

/** Invalid text never turns a restricted grant into an all-kind grant. */
fun parseEventKinds(text: String): Set<Int>? {
    if (text.isBlank() || text.length > 4096) return null
    val tokens = text.split(',')
    if (tokens.size > 256) return null
    val kinds = tokens.map { token ->
        val value = token.trim()
        if (!value.matches(Regex("[0-9]{1,5}"))) return null
        value.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
    }
    return kinds.toSet()
}

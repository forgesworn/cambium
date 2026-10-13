package dev.forgesworn.cambium.log

import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Stable local references are deliberately different on each installation. */
object IdentityReferences {
    const val SALT_BYTES = 32
    private val PUBLIC_KEY = Regex("[a-fA-F0-9]{64}")
    fun isPublicKey(value: String): Boolean = PUBLIC_KEY.matches(value)

    fun create(salt: ByteArray, pubkeyHex: String): String? {
        if (salt.size != SALT_BYTES || !isPublicKey(pubkeyHex)) return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(pubkeyHex.lowercase(Locale.ROOT).toByteArray(StandardCharsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Legacy labels are kept separate because there is no safe way to infer their public identity. */
object ActivityLogIdentity {
    data class Group(val key: String, val label: String?, val isLegacy: Boolean)

    fun keyFor(entry: ActivityLogEntry): String? = entry.identityReference?.let { "reference:$it" }
        ?: entry.identityLabel?.let { "legacy:$it" }

    fun groupsFor(entries: List<ActivityLogEntry>): List<Group> = entries
        .filter { keyFor(it) != null }
        .groupBy { keyFor(it)!! }
        .map { (key, rows) ->
            val newest = rows.maxBy { it.timestampMillis }
            Group(key, newest.identityLabel, newest.identityReference == null)
        }
        .sortedWith(compareBy<Group> { it.label.orEmpty() }.thenBy { it.key })
}

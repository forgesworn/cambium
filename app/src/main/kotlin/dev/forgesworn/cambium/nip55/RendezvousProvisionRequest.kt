package dev.forgesworn.cambium.nip55

import java.util.Base64
import kotlinx.serialization.json.*

/** The single supported child handoff. Only public ceremony bindings cross NIP-55. */
data class RendezvousProvisionRequest(
    val expectedPersona: String,
    val targetDevice: String,
    val index: Long,
    val nonce: String,
    val expiresAt: Long,
) {
    fun validAt(now: Long): Boolean = HEX.matches(expectedPersona) && HEX.matches(targetDevice) && index in 0..0xffff_ffffL &&
        nonce.matches(Regex("[A-Za-z0-9_-]{21}[AQgw]")) && now > 0 && expiresAt <= 9_007_199_254_740_991L && expiresAt > now && expiresAt - now <= 600
    fun params(): JsonArray = buildJsonArray {
        add(targetDevice); add(index); add(nonce); add(expiresAt)
    }
    fun acceptsEnvelope(raw: String, now: Long): Boolean {
        if (!validAt(now) || raw.length !in 1..8192) return false
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return false
        if (obj.keys != setOf("v", "p", "d", "rz", "u", "i", "n", "e", "c") || obj.toString() != raw) return false
        fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun uint(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString && UINT.matches(it.content) }?.longOrNull
        return uint("v") == 1L && text("p") == expectedPersona && text("d") == targetDevice &&
            text("rz")?.matches(HEX) == true && text("u") == "rendezvous" && uint("i") == index &&
            text("n") == nonce && uint("e") == expiresAt && text("c")?.let { ciphertext -> runCatching {
                val bytes = Base64.getDecoder().decode(ciphertext)
                ciphertext.length <= 6144 && bytes.size >= 99 && bytes[0] == 2.toByte() && Base64.getEncoder().encodeToString(bytes) == ciphertext
            }.getOrDefault(false) } == true
    }
    companion object {
        const val TYPE = "heartwood_provision_rendezvous"
        private val HEX = Regex("[0-9a-f]{64}")
        private val UINT = Regex("0|[1-9][0-9]{0,15}")
        fun read(raw: String, now: Long): RendezvousProvisionRequest? = runCatching {
            require(raw.length in 1..1024)
            val obj = Json.parseToJsonElement(raw) as JsonObject
            require(obj.keys == setOf("v", "expectedPersona", "targetDevice", "index", "nonce", "expiresAt"))
            // Compact JSON also refuses duplicate/escaped keys and alternate number spellings.
            require(obj.toString() == raw)
            fun text(key: String) = (obj[key] as JsonPrimitive).also { require(it.isString) }.content
            fun uint(key: String) = (obj[key] as JsonPrimitive).also { require(!it.isString && UINT.matches(it.content)) }.content.toLong()
            require(uint("v") == 1L)
            val persona = text("expectedPersona").also { require(HEX.matches(it)) }
            val target = text("targetDevice").also { require(HEX.matches(it)) }
            val index = uint("index").also { require(it in 0..0xffff_ffffL) }
            val nonce = text("nonce").also {
                require(it.length == 22 && Regex("[A-Za-z0-9_-]{21}[AQgw]").matches(it))
                require(Base64.getUrlDecoder().decode(it).size == 16)
            }
            val expiry = uint("expiresAt").also { require(it <= 9_007_199_254_740_991L) }
            RendezvousProvisionRequest(persona, target, index, nonce, expiry).also { require(it.validAt(now)) }
        }.getOrNull()
    }
}

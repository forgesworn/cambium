package dev.forgesworn.cambium.nip55

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** NIP-55's results-array entries, including the legacy Amber signature alias. */
@Serializable
data class NativeSignerResult(
    val `package`: String,
    val id: String? = null,
    val result: String? = null,
    val signature: String? = null,
    val rejected: Boolean? = null,
    val unavailable: Boolean? = null,
)

object NativeBatchResultBuilder {
    private val signatureHex = Regex("[0-9a-fA-F]{128}")

    /** Missing/malformed signatures fail closed. The SDK supplies cryptographic validation. */
    fun success(request: Nip55Request, value: String, signerPackage: String): NativeSignerResult? {
        val signature = if (request is Nip55Request.SignEvent) {
            val field = runCatching { Json.parseToJsonElement(value).jsonObject["sig"] as? JsonPrimitive }.getOrNull()
            field?.takeIf { it.isString && signatureHex.matches(it.content) }?.content ?: return null
        } else null
        return NativeSignerResult(signerPackage, request.id, signature ?: value, signature)
    }
}

/** Bounded per-activity result collection. Never shared between activities/callers. */
class NativeBatchResults(private val maxBytes: Int = MAX_RESULT_BYTES) {
    private val entries = mutableListOf<NativeSignerResult>()
    val size: Int get() = entries.size

    fun append(result: NativeSignerResult): Boolean {
        if (entries.size >= MAX_REQUESTS) return false
        val prospective = Json.encodeToString(entries + result)
        // Keep room to return correlated rejection rows if a later result is oversized.
        val reserved = if (maxBytes == MAX_RESULT_BYTES && result.rejected != true)
            (MAX_REQUESTS - entries.size - 1) * 2048 else 0
        if (prospective.toByteArray(Charsets.UTF_8).size > maxBytes - reserved) return false
        entries += result
        return true
    }

    fun toJson(): String = Json.encodeToString(entries)

    companion object {
        const val MAX_REQUESTS = 32
        const val MAX_INPUT_BYTES = 128 * 1024
        const val MAX_RESULT_BYTES = 384 * 1024
        const val MAX_ID_CHARS = 256
    }
}

/** Reused intents do not prove sender identity on older Android versions. A known foreign
 * sender is rejected; an unattributable sender always needs visible one-shot consent. */
object BatchCallerGate {
    enum class Decision { REMEMBERED_PERMISSION, ONE_SHOT, REJECT }
    fun decide(resultRecipient: String?, attributedSender: String?): Decision = when {
        resultRecipient == null -> Decision.REJECT
        attributedSender == null -> Decision.ONE_SHOT
        attributedSender == resultRecipient -> Decision.REMEMBERED_PERMISSION
        else -> Decision.REJECT
    }
}

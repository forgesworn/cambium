package dev.forgesworn.cambium.log

import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.isDeterministicDecryptFailure
import dev.forgesworn.cambium.signer.HeartwoodOutcome
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.isPolicyRefusal
import kotlinx.serialization.Serializable

/**
 * One entry in the on-phone activity log -- metadata only, **never** a payload, plaintext or
 * ciphertext: [callingPackage], the NIP-55 [method] name, [eventKind] (only ever set for
 * `sign_event`, and only the numeric kind, not the event itself), which paired identity answered
 * ([identityLabel], a display label, not the raw pubkey), and the [outcome]. [identityReference]
 * is a per-install opaque grouping reference so renames and duplicate labels cannot mix identities.
 * The point is
 * reassurance/transparency ("what has Cambium done on my behalf"), not an audit trail of what was
 * actually signed or decrypted -- Cambium holds no keys and sees ciphertext it cannot read, and
 * this log does not change that.
 */
@Serializable
data class ActivityLogEntry(
    val timestampMillis: Long,
    val callingPackage: String,
    val method: String,
    val eventKind: Int? = null,
    val identityLabel: String? = null,
    val outcome: Outcome,
    val failureCategory: FailureCategory? = null,
    val durationMillis: Long? = null,
    val identityReference: String? = null,
) {
    @Serializable
    enum class Outcome { SIGNED, ANSWERED_FROM_CACHE, REJECTED_POLICY, REJECTED_USER, FAILED }

    @Serializable
    enum class FailureCategory {
        NOT_CONNECTED, BUSY, TIMEOUT, INVALID_INPUT, POLICY_REFUSAL, DECRYPTION_FAILED, PROTOCOL, OTHER
    }
}

/**
 * Pure Kotlin (no Android): the capped append/rotate logic and the [HeartwoodOutcome]-to-[ActivityLogEntry.Outcome]
 * classification, kept separate from [ActivityLogStore]'s file I/O so both are JVM-testable.
 * [HeartwoodOutcome]/[HeartwoodResult]/`isPolicyRefusal` are plain Kotlin types/functions with
 * nothing Android or rust-nostr in their own signatures (same reasoning as `nip57/PrivateZap.kt`'s
 * dependency on `signer`), so referencing them here does not affect this file's testability.
 */
object ActivityLog {
    const val MAX_ENTRIES = 500

    /** [existing] is oldest-first, so a rolling cap is just keeping the tail after appending
     * [entry]. */
    fun append(existing: List<ActivityLogEntry>, entry: ActivityLogEntry, maxEntries: Int = MAX_ENTRIES): List<ActivityLogEntry> {
        val updated = existing + entry
        return if (updated.size > maxEntries) updated.takeLast(maxEntries) else updated
    }

    /** Structured metadata only: error messages are inspected but never retained. */
    fun failureCategoryFor(outcome: HeartwoodOutcome<String>): ActivityLogEntry.FailureCategory? {
        val failure = outcome.result as? HeartwoodResult.Failure ?: return null
        return when (val error = failure.error) {
            HeartwoodError.NotConnected -> ActivityLogEntry.FailureCategory.NOT_CONNECTED
            HeartwoodError.Busy -> ActivityLogEntry.FailureCategory.BUSY
            HeartwoodError.Timeout -> ActivityLogEntry.FailureCategory.TIMEOUT
            is HeartwoodError.InvalidInput -> ActivityLogEntry.FailureCategory.INVALID_INPUT
            is HeartwoodError.Protocol -> when {
                isPolicyRefusal(error) -> ActivityLogEntry.FailureCategory.POLICY_REFUSAL
                isDeterministicDecryptFailure(error) -> ActivityLogEntry.FailureCategory.DECRYPTION_FAILED
                else -> ActivityLogEntry.FailureCategory.PROTOCOL
            }
            else -> ActivityLogEntry.FailureCategory.OTHER
        }
    }

    data class RequestDiagnostics(
        val outcome: ActivityLogEntry.Outcome,
        val failureCategory: ActivityLogEntry.FailureCategory?,
        val durationMillis: Long,
    )

    fun diagnosticsFor(outcome: HeartwoodOutcome<String>, elapsedMillis: Long): RequestDiagnostics =
        RequestDiagnostics(outcomeFor(outcome), failureCategoryFor(outcome), elapsedMillis.coerceAtLeast(0))

    /** A cache hit is [ActivityLogEntry.Outcome.ANSWERED_FROM_CACHE] regardless of how old the
     * cached answer is; an explicit Heartwood policy refusal (see `isPolicyRefusal`) is
     * [ActivityLogEntry.Outcome.REJECTED_POLICY]; every other failure (timeout, connect error, a
     * deterministic decrypt failure) is [ActivityLogEntry.Outcome.FAILED] -- none of those are a
     * *policy* refusal specifically, just something that did not succeed. */
    fun outcomeFor(outcome: HeartwoodOutcome<String>): ActivityLogEntry.Outcome = when (val result = outcome.result) {
        is HeartwoodResult.Success -> if (outcome is HeartwoodOutcome.Cached) {
            ActivityLogEntry.Outcome.ANSWERED_FROM_CACHE
        } else {
            ActivityLogEntry.Outcome.SIGNED
        }
        is HeartwoodResult.Failure -> if (isPolicyRefusal(result.error)) {
            ActivityLogEntry.Outcome.REJECTED_POLICY
        } else {
            ActivityLogEntry.Outcome.FAILED
        }
    }
}

package dev.forgesworn.cambium.nip55

import dev.forgesworn.cambium.pairing.AppPermission
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.signer.HeartwoodOutcome
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.isDeterministicDecryptFailure
import dev.forgesworn.cambium.signer.isPolicyRefusal

/**
 * The pure decision tables behind [SignerProvider]'s three-way cursor contract: a definitive
 * result, a terminal `rejected` cursor (clients must not fall back to the intent), or `null`
 * (defer to the intent). Extracted from the provider so the contract is testable on the host JVM,
 * which the provider itself is not (an Android class whose imports reach rust-nostr). Pure
 * Kotlin: no Android. The provider keeps its own diagnostic logging around these calls.
 */
object ProviderGate {

    /** NIP-37 draft events, declined silently and terminally on the silent path -- Amethyst
     * auto-saves a draft roughly every 2s while the user types, and forwarding each one to a
     * 1-2s hardware round trip buried real requests behind a flood of drafts in testing.
     * Hardcoded on for now; a settings toggle can replace this later. */
    const val NIP37_DRAFT_KIND = 31234

    sealed interface Caller {
        data class Approved(val packageName: String, val boundIdentityPubkeyHex: String?) : Caller
        /** Remembered denial: answer the terminal `rejected` cursor for every authority, without
         * ever resolving a pairing or touching the queue. */
        data object Rejected : Caller
        /** Unresolvable calling package, or no remembered choice yet: answer `null`, deferring to
         * the intent (where the approval sheet lives). */
        data object Defer : Caller
    }

    /** [permissionFor] is only consulted when [callingPackage] is resolvable -- there is no
     * package to look up otherwise. */
    fun resolveCaller(callingPackage: String?, permissionFor: (String) -> AppPermission?): Caller {
        if (callingPackage == null) return Caller.Defer
        return when (val permission = permissionFor(callingPackage)) {
            null -> Caller.Defer
            else -> when (permission.state) {
                AppPermissionState.APPROVED -> Caller.Approved(callingPackage, permission.boundIdentityPubkeyHex)
                AppPermissionState.DENIED -> Caller.Rejected
            }
        }
    }

    sealed interface Answer {
        data class Result(val value: String) : Answer
        data object Rejected : Answer
        data object Defer : Answer
    }

    /**
     * Maps a forward's outcome onto the cursor contract. `null` (queue full, or timed out) and
     * any transient/repairable failure defer to the intent, where a retry might succeed or the
     * user at least sees what happened. Only an explicit Heartwood policy refusal (see
     * [isPolicyRefusal]) or a deterministic decrypt failure (see [isDeterministicDecryptFailure])
     * answer `rejected` -- both are terminal, and anything else answered terminally would block a
     * request that a retry could have served.
     */
    fun answerFor(outcome: HeartwoodOutcome<String>?): Answer {
        val result = outcome?.result ?: return Answer.Defer
        return when (result) {
            is HeartwoodResult.Success -> Answer.Result(result.value)
            is HeartwoodResult.Failure ->
                if (isPolicyRefusal(result.error) || isDeterministicDecryptFailure(result.error)) {
                    Answer.Rejected
                } else {
                    Answer.Defer
                }
        }
    }

    fun isDeclinedDraft(eventKind: Int?): Boolean = eventKind == NIP37_DRAFT_KIND
}

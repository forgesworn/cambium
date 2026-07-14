package dev.forgesworn.cambium.nip55

import dev.forgesworn.cambium.pairing.AppPermission
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.pairing.IdentityRouting
import dev.forgesworn.cambium.pairing.Pairing

/**
 * The pure decision table behind [SignerActivity]: what an incoming `nostrsigner:` intent should
 * do ([Plan]), and whether the window handling the *first* intent needs any decorations at all
 * ([silentFor]). Extracted from the activity so the look-ahead that picks the window theme and
 * the dispatch that later acts on the request consume one shared decision and can never disagree
 * -- and so the table is testable on the host JVM, which the activity itself is not (an Android
 * class whose imports reach rust-nostr). Pure Kotlin: no Android.
 */
object IntentGate {

    sealed interface Plan {
        /** Route to [pairing]: forward on HeartwoodSession's worker (or answer `get_public_key`
         * locally from the pairing record). */
        data class Forward(val pairing: Pairing) : Plan

        /** Show the Approve/Decline sheet -- only possible on a window that set up decorations,
         * see [plan]'s `canAsk`. */
        data object AskUser : Plan

        /** Reject outright, no Heartwood call; [reason] decides logging and the visible-path
         * not-paired toast. */
        data class Reject(val reason: RejectReason) : Plan
    }

    enum class RejectReason {
        /** Unrecognised `type`, or a required field missing -- [Nip55Request.from] returned null.
         * Deliberately unlogged (see `SignerActivity.logActivity`'s doc). */
        MALFORMED_REQUEST,

        /** Nothing paired at all. Unlogged; shows the not-paired toast on a visible window. */
        NOTHING_PAIRED,

        /** The caller has a remembered denial. Logged as `REJECTED_USER`. */
        DENIED_CALLER,

        /** An approved caller whose identity routing did not resolve, on a window that can no
         * longer ask (see [plan]'s `canAsk`). Logged as `FAILED` -- rejecting is safer than
         * guessing an identity the request did not name. */
        UNRESOLVED_IDENTITY,
    }

    /**
     * Decides what [parsed] should do for a caller with [permission] (null = no remembered
     * choice). Precedence mirrors what `SignerActivity.handleIncomingIntent` always did:
     * unparsable, then nothing-paired, then the remembered choice; an approved caller routes via
     * [IdentityRouting.resolve] and forwards only on [IdentityRouting.Result.Resolved] -- any
     * other routing outcome asks rather than guesses.
     *
     * [canAsk] is true for the first intent of an activity instance, where the window's theme is
     * still decided by what this returns, and false for a later `onNewIntent`-delivered request
     * arriving on an already-invisible window -- there is no reliable way to grow a sheet's
     * decorations onto an invisible window after the fact, so an approved caller whose routing
     * does not resolve there is rejected instead.
     */
    fun plan(
        permission: AppPermission?,
        pairings: List<Pairing>,
        parsed: Nip55Request?,
        canAsk: Boolean,
    ): Plan {
        if (parsed == null) return Plan.Reject(RejectReason.MALFORMED_REQUEST)
        if (pairings.isEmpty()) return Plan.Reject(RejectReason.NOTHING_PAIRED)
        return when (permission?.state) {
            null -> Plan.AskUser
            AppPermissionState.DENIED -> Plan.Reject(RejectReason.DENIED_CALLER)
            AppPermissionState.APPROVED ->
                when (val routed = IdentityRouting.resolve(parsed.currentUser, permission.boundIdentityPubkeyHex, pairings)) {
                    is IdentityRouting.Result.Resolved -> Plan.Forward(routed.pairing)
                    else -> if (canAsk) Plan.AskUser else Plan.Reject(RejectReason.UNRESOLVED_IDENTITY)
                }
        }
    }

    /**
     * Whether the first intent needs no window decorations at all -- decided once, before any
     * window setup (see `SignerActivity`'s class doc for why it cannot be re-evaluated later). A
     * caller with no remembered choice is never silent, even when [plan] already knows the
     * request will be rejected: first contact keeps the visible window, so a brand-new user's
     * pairing mistake gets the not-paired toast rather than a silent no-op.
     */
    fun silentFor(permission: AppPermission?, plan: Plan): Boolean =
        permission != null && plan !is Plan.AskUser
}

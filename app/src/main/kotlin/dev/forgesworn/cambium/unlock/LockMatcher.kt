package dev.forgesworn.cambium.unlock

import kotlinx.serialization.Serializable

/**
 * One board this phone can unlock. [phoneKeyHex] is K (reads lock messages, unlocks nothing);
 * the slot secret S is only held sealed, as [sealedSecretB64] under the Keystore key [keyAlias],
 * which a strong biometric has to release. [signerPubkeyHex] is the paired identity it was set up
 * from, used only to name the board and to say when Cambium last heard it; the board itself never
 * learns it. [relays] is the union of every relay list the phone has been told about, since the
 * lock message itself carries the board's current list.
 */
@Serializable
data class UnlockEnrolment(
    val id: Long,
    val boardLabel: String,
    val signerPubkeyHex: String?,
    val relays: List<String>,
    val phoneKeyHex: String,
    val keyAlias: String,
    val sealedSecretB64: String,
    val enrolledAtMillis: Long,
    val last: LastPrompt? = null,
)

/** A kind-24135 as it came off a relay, already signature-checked by the relay client. */
data class RawAnnouncement(
    val eventId: String,
    val authorHex: String,
    val createdAt: Long,
    val content: String,
    val hint: String?,
)

data class LockMatch(
    val enrolment: UnlockEnrolment,
    val context: LockContext,
    val announcement: RawAnnouncement,
    val verdict: Verdict,
)

/**
 * Matches a lock message against every enrolled board. The subscription is unfiltered (every
 * 24135 on the relay, so the relay learns nothing about which board this phone waits for), which
 * means almost everything that arrives here belongs to someone else and is dropped at the hint,
 * one HMAC per enrolment. Only a hint match costs a decryption.
 */
object LockMatcher {
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun match(raw: RawAnnouncement, enrolments: List<UnlockEnrolment>, nowSecs: Long): LockMatch? {
        val hint = raw.hint ?: return null
        if (!HEX64.matches(raw.authorHex)) return null
        val author = raw.authorHex.hexToBytesOrNull() ?: return null
        for (enrolment in enrolments) {
            val k = enrolment.phoneKeyHex.hexToBytesOrNull() ?: continue
            try {
                if (!PhoneUnlock.hintMatches(k, author, hint)) continue
                val context = PhoneUnlock.openContext(k, author, raw.content) ?: continue
                if (context.id != enrolment.id) continue
                val verdict = PhoneUnlock.judge(context, raw.authorHex, raw.createdAt, nowSecs, enrolment.last)
                return LockMatch(enrolment, context, raw, verdict)
            } finally {
                k.fill(0)
            }
        }
        return null
    }

    /** The relays to listen on: every enrolment's list, deduplicated, valid URLs only. */
    fun relayUnion(enrolments: List<UnlockEnrolment>, extra: List<String> = emptyList()): List<String> =
        (enrolments.flatMap { it.relays } + extra).map { it.trimEnd('/') }.filter(::isRelayUrl).distinct()

    /** Follows a relay change the board announced, keeping every relay already known. */
    fun withRelaysFrom(enrolment: UnlockEnrolment, context: LockContext): UnlockEnrolment {
        val merged = (enrolment.relays + context.relays.filter(::isRelayUrl)).map { it.trimEnd('/') }.distinct()
        return if (merged == enrolment.relays) enrolment else enrolment.copy(relays = merged)
    }
}

/**
 * The "Heartwood has gone quiet" rule, from the keep-alive's scheduled pings only (Cambium never
 * pings because of something it saw on a relay: that would link a lock message to this phone's
 * stable NIP-46 key by timing). An identity is quiet after [QUIET_AFTER_FAILURES] failed pings in a
 * row, and only if it has answered at least once, so a pairing that never worked does not alert.
 */
object Reachability {
    const val QUIET_AFTER_FAILURES = 2

    @Serializable
    data class Record(val lastSuccessAtMillis: Long?, val consecutiveFailures: Int)

    fun afterPing(previous: Record?, ok: Boolean, nowMillis: Long): Record =
        if (ok) Record(nowMillis, 0)
        else Record(previous?.lastSuccessAtMillis, (previous?.consecutiveFailures ?: 0) + 1)

    fun isQuiet(record: Record?): Boolean =
        record?.lastSuccessAtMillis != null && record.consecutiveFailures >= QUIET_AFTER_FAILURES
}

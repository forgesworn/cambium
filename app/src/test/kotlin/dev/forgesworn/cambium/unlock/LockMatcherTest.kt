package dev.forgesworn.cambium.unlock

import dev.forgesworn.cambium.toHex
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LockMatcherTest {

    private val now = 1_800_000_000L
    private val authorHex = "09".repeat(32)
    private val author = authorHex.hexToBytesOrNull()!!

    private fun enrolment(id: Long, secretByte: Int, last: LastPrompt? = null) = UnlockEnrolment(
        id = id,
        boardLabel = "desk",
        signerPubkeyHex = null,
        relays = listOf("wss://relay.example"),
        phoneKeyHex = PhoneUnlock.phoneKey(ByteArray(32) { secretByte.toByte() }).toHex(),
        keyAlias = "alias-$id",
        sealedSecretB64 = "",
        enrolledAtMillis = 0,
        last = last,
    )

    private fun announcement(forEnrolment: UnlockEnrolment, id: Long = forEnrolment.id, boot: Long = 5, createdAt: Long = now): RawAnnouncement {
        val k = forEnrolment.phoneKeyHex.hexToBytesOrNull()!!
        val context = LockContext(1, "locked", id, boot, "power-on", "home", "", "0.18.0-beta.17", listOf("wss://new.example"))
        val content = PhoneUnlock.sealContext(k, author, Json.encodeToString(LockContext.serializer(), context), ByteArray(12))
        return RawAnnouncement("e".repeat(64), authorHex, createdAt, content, PhoneUnlock.hint(k, author))
    }

    @Test
    fun `an announcement is matched to the board it was sealed for`() {
        val desk = enrolment(1, 1)
        val attic = enrolment(2, 2)
        val match = LockMatcher.match(announcement(attic), listOf(desk, attic), now)
        assertNotNull(match)
        assertSame(attic, match.enrolment)
        assertEquals(Verdict.PROMPT, match.verdict)
        assertEquals(5, match.context.boot)
    }

    @Test
    fun `someone else's announcement, or one without a hint, matches nothing`() {
        val desk = enrolment(1, 1)
        assertNull(LockMatcher.match(announcement(enrolment(3, 3)), listOf(desk), now))
        assertNull(LockMatcher.match(announcement(desk).copy(hint = null), listOf(desk), now))
        assertNull(LockMatcher.match(announcement(desk).copy(authorHex = "zz"), listOf(desk), now))
    }

    @Test
    fun `a message sealed for another record id on the same key is refused`() {
        val desk = enrolment(1, 1)
        assertNull(LockMatcher.match(announcement(desk, id = 99), listOf(desk), now))
    }

    @Test
    fun `the verdict uses the enrolment's last prompt`() {
        val desk = enrolment(1, 1, last = LastPrompt(5, authorHex))
        assertEquals(Verdict.DUPLICATE, LockMatcher.match(announcement(desk), listOf(desk), now)?.verdict)
        assertEquals(Verdict.PROMPT, LockMatcher.match(announcement(desk, boot = 6), listOf(desk), now)?.verdict)
        assertEquals(Verdict.REPLAY, LockMatcher.match(announcement(desk, boot = 4), listOf(desk), now)?.verdict)
        assertEquals(Verdict.STALE, LockMatcher.match(announcement(desk, boot = 6, createdAt = now - 600), listOf(desk), now)?.verdict)
    }

    @Test
    fun `relay lists are merged, never shrunk`() {
        val desk = enrolment(1, 1)
        val context = LockContext(1, "locked", 1, 5, "", "", "", "", listOf("wss://relay.example/", "wss://new.example", "http://no"))
        val merged = LockMatcher.withRelaysFrom(desk, context)
        assertEquals(listOf("wss://relay.example", "wss://new.example"), merged.relays)
        assertSame(merged, LockMatcher.withRelaysFrom(merged, context))
        assertEquals(
            listOf("wss://relay.example", "wss://new.example", "wss://x"),
            LockMatcher.relayUnion(listOf(desk, merged), listOf("wss://x/")),
        )
    }

    @Test
    fun `a board is quiet after two failed pings, only if it ever answered`() {
        var record: Reachability.Record? = null
        record = Reachability.afterPing(record, ok = false, nowMillis = 1)
        record = Reachability.afterPing(record, ok = false, nowMillis = 2)
        assertFalse(Reachability.isQuiet(record), "never heard: no alert")
        record = Reachability.afterPing(record, ok = true, nowMillis = 3)
        record = Reachability.afterPing(record, ok = false, nowMillis = 4)
        assertFalse(Reachability.isQuiet(record))
        record = Reachability.afterPing(record, ok = false, nowMillis = 5)
        assertTrue(Reachability.isQuiet(record))
        assertEquals(3, record.lastSuccessAtMillis)
        assertFalse(Reachability.isQuiet(Reachability.afterPing(record, ok = true, nowMillis = 6)))
    }
}

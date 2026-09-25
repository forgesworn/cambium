package dev.forgesworn.cambium.unlock

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenWordsTest {

    /**
     * Pins `SpokenWords.WORDLIST` against heartwood-esp32's `common/src/spoken_words.txt` -- the
     * two must stay byte-for-byte identical, since the request words the board and Cambium each
     * derive from the same enrolment pubkey only match if they draw from the same list in the
     * same order.
     */
    @Test
    fun `the word list matches the firmware's copy`() {
        assertEquals(2048, SpokenWords.WORDLIST.size)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(SpokenWords.WORDLIST.joinToString("\n").toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        assertEquals("0334930ebdfbc76e81ec914515d7567ca85738a6bf3069249d97df951d44661c", hex)
    }
}

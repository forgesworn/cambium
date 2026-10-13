package dev.forgesworn.cambium.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventKindSelectionTest {
    @Test fun `valid kinds allow whitespace and remove duplicates`() {
        assertEquals(setOf(0, 1, 7, 65535), parseEventKinds("0, 1,7,1,65535"))
    }
    @Test fun `malformed restrictions never become broad permission`() {
        listOf("", " ", "1,", "1,,7", "-1", "65536", "all", "1.0", "+1", "000001", "1;7").forEach {
            assertNull(it, parseEventKinds(it))
        }
    }
}

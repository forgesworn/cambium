package dev.forgesworn.cambium

import kotlin.test.Test
import kotlin.test.assertEquals

class HexUtilsTest {

    @Test
    fun `encodes bytes as lowercase hex`() {
        assertEquals("deadbeef", byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).toHex())
    }

    @Test
    fun `pads single-digit bytes with a leading zero`() {
        assertEquals("000102", byteArrayOf(0, 1, 2).toHex())
    }

    @Test
    fun `an empty array encodes to an empty string`() {
        assertEquals("", ByteArray(0).toHex())
    }
}

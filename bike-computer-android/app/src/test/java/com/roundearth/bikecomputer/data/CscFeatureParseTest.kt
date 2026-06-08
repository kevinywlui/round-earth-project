package com.roundearth.bikecomputer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [CscBleDataSource.parseWheelSupported], the pure decode of the CSC Feature
 * characteristic (0x2A5C, 16-bit little-endian) into "does this sensor report wheel revs?".
 */
class CscFeatureParseTest {

    private fun parse(bytes: ByteArray?) = CscBleDataSource.parseWheelSupported(bytes)

    @Test
    fun nullOrTooShortYieldsNull() {
        assertNull(parse(null))
        assertNull(parse(ByteArray(0)))
        assertNull(parse(byteArrayOf(0x01))) // only one byte; need two
    }

    @Test
    fun wheelBitSetIsSupported() {
        // 0x0001, little-endian → wheel-revolution data supported.
        assertEquals(true, parse(byteArrayOf(0x01, 0x00)))
    }

    @Test
    fun wheelBitClearIsUnsupported() {
        // 0x0002 = crank-only (bit 1), no wheel bit.
        assertEquals(false, parse(byteArrayOf(0x02, 0x00)))
        // 0x0000 = nothing advertised.
        assertEquals(false, parse(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun wheelBitIsReadFromTheLowByteWithOtherBitsSet() {
        // 0x0003 = wheel + crank; wheel bit still set. High byte ignored for this check.
        assertEquals(true, parse(byteArrayOf(0x03, 0x00)))
        assertEquals(true, parse(byteArrayOf(0x01, 0xFF.toByte())))
    }

    @Test
    fun trailingBytesBeyondTheFirstTwoAreIgnored() {
        // The parser checks `size < 2`, not `!= 2`, so it tolerates longer payloads
        // (a future spec revision or a chatty sensor) and only reads the low two bytes.
        assertEquals(true, parse(byteArrayOf(0x01, 0x00, 0xFF.toByte())))
        assertEquals(false, parse(byteArrayOf(0x02, 0x00, 0x01)))
    }
}

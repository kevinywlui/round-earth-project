package com.roundearth.bikecomputer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CscMeasurementDecoder], exercising the CSC Measurement byte
 * layout and the speed/cadence math without any BLE hardware.
 *
 * Packet layout (CSC Profile §4.4), all little-endian:
 *   flags(1) [ wheelRevs(u32) wheelTime(u16) ] [ crankRevs(u16) crankTime(u16) ]
 * Event times are in 1/1024 s units.
 */
class CscMeasurementDecoderTest {

    private val circumference = 2.0 // meters, chosen for round numbers

    private fun wheelPacket(revs: Long, time: Int): ByteArray = byteArrayOf(
        0x01,
        (revs and 0xFF).toByte(),
        ((revs shr 8) and 0xFF).toByte(),
        ((revs shr 16) and 0xFF).toByte(),
        ((revs shr 24) and 0xFF).toByte(),
        (time and 0xFF).toByte(),
        ((time shr 8) and 0xFF).toByte(),
    )

    private fun crankPacket(revs: Int, time: Int): ByteArray = byteArrayOf(
        0x02,
        (revs and 0xFF).toByte(),
        ((revs shr 8) and 0xFF).toByte(),
        (time and 0xFF).toByte(),
        ((time shr 8) and 0xFF).toByte(),
    )

    private fun comboPacket(wRevs: Long, wTime: Int, cRevs: Int, cTime: Int): ByteArray = byteArrayOf(
        0x03,
        (wRevs and 0xFF).toByte(),
        ((wRevs shr 8) and 0xFF).toByte(),
        ((wRevs shr 16) and 0xFF).toByte(),
        ((wRevs shr 24) and 0xFF).toByte(),
        (wTime and 0xFF).toByte(),
        ((wTime shr 8) and 0xFF).toByte(),
        (cRevs and 0xFF).toByte(),
        ((cRevs shr 8) and 0xFF).toByte(),
        (cTime and 0xFF).toByte(),
        ((cTime shr 8) and 0xFF).toByte(),
    )

    @Test
    fun firstWheelPacketHasNoSpeed() {
        val decoder = CscMeasurementDecoder()
        val result = decoder.decode(wheelPacket(revs = 1000, time = 5000), circumference)
        assertNull("no baseline yet", result.speedKph)
        assertEquals(0.0, result.distanceMeters, 1e-9)
    }

    @Test
    fun twoWheelPacketsGiveCorrectSpeedAndDistance() {
        val decoder = CscMeasurementDecoder()
        decoder.decode(wheelPacket(revs = 1000, time = 5000), circumference)
        // +3 revs over 800 ticks → 0.78125 s; distance = 3 × 2.0 = 6.0 m
        val result = decoder.decode(wheelPacket(revs = 1003, time = 5800), circumference)

        assertEquals(6.0, result.distanceMeters, 1e-9)
        val expectedKph = 6.0 / (800.0 / 1024.0) * 3.6 // 27.648
        assertEquals(expectedKph, result.speedKph!!, 1e-6)
    }

    @Test
    fun wheelRawFieldsAreSurfacedForPersistence() {
        val decoder = CscMeasurementDecoder()
        // Even the first packet exposes the raw count/time (needed for the
        // lossless time-series) while speed stays null until there's a baseline.
        val first = decoder.decode(wheelPacket(revs = 1000, time = 5000), circumference)
        assertEquals(1000L, first.wheelCumulativeRevs)
        assertEquals(5000, first.wheelEventTime1024)
        assertNull(first.speedKph)

        val second = decoder.decode(wheelPacket(revs = 1003, time = 5800), circumference)
        assertEquals(1003L, second.wheelCumulativeRevs)
        assertEquals(5800, second.wheelEventTime1024)
    }

    @Test
    fun wheelEventTimeWrapIsHandled() {
        val decoder = CscMeasurementDecoder()
        decoder.decode(wheelPacket(revs = 10, time = 65000), circumference)
        // time wraps past 65535: 200 - 65000 ≡ 736 ticks (mod 65536)
        val result = decoder.decode(wheelPacket(revs = 12, time = 200), circumference)

        val dTicks = (200 - 65000) and 0xFFFF // 736
        assertEquals(736, dTicks)
        val expectedKph = (2 * 2.0) / (dTicks / 1024.0) * 3.6
        assertEquals(expectedKph, result.speedKph!!, 1e-6)
    }

    @Test
    fun coastingSameRevsGivesNoSpeed() {
        val decoder = CscMeasurementDecoder()
        decoder.decode(wheelPacket(revs = 500, time = 1000), circumference)
        val result = decoder.decode(wheelPacket(revs = 500, time = 2000), circumference)

        assertNull(result.speedKph)
        assertEquals(0.0, result.distanceMeters, 1e-9)
    }

    @Test
    fun sensorRebootResetsBaselineWithoutNegativeDistance() {
        val decoder = CscMeasurementDecoder()
        decoder.decode(wheelPacket(revs = 9000, time = 60000), circumference)
        // device reboots, count restarts well below the previous value
        val reboot = decoder.decode(wheelPacket(revs = 2, time = 100), circumference)
        assertNull("no speed across a reboot discontinuity", reboot.speedKph)
        assertEquals(0.0, reboot.distanceMeters, 1e-9)

        // subsequent packets resume normally from the new baseline
        val next = decoder.decode(wheelPacket(revs = 5, time = 1124), circumference)
        assertEquals(6.0, next.distanceMeters, 1e-9) // 3 revs × 2.0 m
    }

    @Test
    fun twoCrankPacketsGiveCorrectCadence() {
        val decoder = CscMeasurementDecoder()
        decoder.decode(crankPacket(revs = 100, time = 1000), circumference)
        // +2 revs over 1024 ticks → 1.0 s → 120 rpm
        val result = decoder.decode(crankPacket(revs = 102, time = 2024), circumference)

        assertEquals(120.0, result.cadenceRpm!!, 1e-6)
        assertNull("crank-only packet carries no speed", result.speedKph)
    }

    @Test
    fun crankRevolutionCountWrapIsHandled() {
        val decoder = CscMeasurementDecoder()
        decoder.decode(crankPacket(revs = 65535, time = 1000), circumference)
        // 16-bit count wraps: 1 - 65535 ≡ 2 revs (mod 65536), over 1024 ticks → 120 rpm
        val result = decoder.decode(crankPacket(revs = 1, time = 2024), circumference)

        assertEquals(120.0, result.cadenceRpm!!, 1e-6)
    }

    @Test
    fun comboPacketYieldsBothSpeedAndCadence() {
        val decoder = CscMeasurementDecoder()
        decoder.decode(comboPacket(wRevs = 1000, wTime = 5000, cRevs = 100, cTime = 1000), circumference)
        val result = decoder.decode(
            comboPacket(wRevs = 1003, wTime = 5800, cRevs = 102, cTime = 2024),
            circumference,
        )

        assertEquals(6.0, result.distanceMeters, 1e-9)
        assertEquals(6.0 / (800.0 / 1024.0) * 3.6, result.speedKph!!, 1e-6)
        assertEquals(120.0, result.cadenceRpm!!, 1e-6)
    }

    @Test
    fun emptyAndTruncatedPacketsAreSafe() {
        val decoder = CscMeasurementDecoder()
        val empty = decoder.decode(ByteArray(0), circumference)
        assertNull(empty.speedKph)
        assertNull(empty.cadenceRpm)

        // wheel flag set but the revolution/time bytes are missing
        val truncated = decoder.decode(byteArrayOf(0x01, 0x00, 0x00), circumference)
        assertNull(truncated.speedKph)
        assertEquals(0.0, truncated.distanceMeters, 1e-9)
    }

    @Test
    fun byteFieldsAreLittleEndian() {
        // 0x2A5B = 10843 across two bytes, low byte first
        assertEquals(10843, CscMeasurementDecoder.readUInt16(byteArrayOf(0x5B, 0x2A), 0))
        // a value above Int range proves the unsigned 32-bit read
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(4_294_967_295L, CscMeasurementDecoder.readUInt32(bytes, 0))
        assertTrue(CscMeasurementDecoder.readUInt32(bytes, 0) > Int.MAX_VALUE)
    }
}

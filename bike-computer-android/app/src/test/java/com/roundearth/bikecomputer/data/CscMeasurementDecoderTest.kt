package com.roundearth.bikecomputer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CscMeasurementDecoder], exercising the CSC Measurement byte
 * layout and the wheel-speed math without any BLE hardware.
 *
 * Packet layout (CSC Profile §4.4), all little-endian:
 *   flags(1) wheelRevs(u32) wheelTime(u16)
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
    fun distanceIsCountedEvenWhenEventTimeDeltaIsZero() {
        // A sensor that batches two revs at the same reported event time: dTicks==0
        // must not lose the traveled distance (speed is simply not derivable).
        val decoder = CscMeasurementDecoder()
        decoder.decode(wheelPacket(revs = 100, time = 5000), circumference)
        val result = decoder.decode(wheelPacket(revs = 102, time = 5000), circumference)

        assertEquals(2 * 2.0, result.distanceMeters, 1e-9)
        assertEquals(2L, result.wheelDeltaRevs)
        assertNull("no speed when the event-time delta is zero", result.speedKph)
    }

    @Test
    fun wheelCountRolloverIsCountedNotDropped() {
        // UInt32 boundary: 0xFFFFFFFF -> 1 is a +2 advance, not a reboot.
        val decoder = CscMeasurementDecoder()
        decoder.decode(wheelPacket(revs = 0xFFFFFFFFL, time = 1000), circumference)
        val result = decoder.decode(wheelPacket(revs = 1, time = 1512), circumference)

        assertEquals(2L, result.wheelDeltaRevs)
        assertEquals(2 * 2.0, result.distanceMeters, 1e-9)
        assertEquals((2 * 2.0) / (512.0 / 1024.0) * 3.6, result.speedKph!!, 1e-6)
    }

    @Test
    fun longGapSuppressesSpeedSpikeButStillCountsDistance() {
        // A 64 s+ stop aliases the 16-bit event time to a tiny dTicks. With the
        // wall-clock hint, speed must be suppressed but distance still counted.
        val decoder = CscMeasurementDecoder()
        decoder.decode(wheelPacket(revs = 1000, time = 1000), circumference, receivedAtMs = 10_000L)
        // event time advanced ~64.05 s -> wraps to a tiny delta; real gap is 64.05 s.
        val result = decoder.decode(wheelPacket(revs = 1001, time = 1052), circumference, receivedAtMs = 74_050L)

        assertEquals(2.0, result.distanceMeters, 1e-9) // 1 rev * 2.0 m
        assertNull("speed suppressed across a >64 s wrap", result.speedKph)
    }

    @Test
    fun monotonicEventTimeAccumulatesAcrossPackets() {
        val decoder = CscMeasurementDecoder()
        val first = decoder.decode(wheelPacket(revs = 1000, time = 5000), circumference)
        assertEquals("first packet is the baseline", 0L, first.cumulativeEventTime1024)
        val second = decoder.decode(wheelPacket(revs = 1003, time = 5800), circumference)
        assertEquals(800L, second.cumulativeEventTime1024) // +800 ticks
        val third = decoder.decode(wheelPacket(revs = 1004, time = 6300), circumference)
        assertEquals(1300L, third.cumulativeEventTime1024) // +500 more
    }

    @Test
    fun monotonicEventTimeUnwrapsThe16BitWrap() {
        // A real 16-bit wrap within 64 s is precise from the event time alone (no wall clock).
        val decoder = CscMeasurementDecoder()
        decoder.decode(wheelPacket(revs = 10, time = 65000), circumference)
        val result = decoder.decode(wheelPacket(revs = 12, time = 200), circumference)
        assertEquals(736L, result.cumulativeEventTime1024) // (200 - 65000) mod 65536
    }

    @Test
    fun monotonicEventTimeEstimatesAnAmbiguousGapFromTheWallClock() {
        // A >64 s stop wraps the event time ambiguously; the accumulator advances by the
        // wall-clock estimate for that one gap instead, staying monotonic.
        val decoder = CscMeasurementDecoder()
        decoder.decode(wheelPacket(revs = 1000, time = 1000), circumference, receivedAtMs = 10_000L)
        val result = decoder.decode(wheelPacket(revs = 1001, time = 1052), circumference, receivedAtMs = 74_050L)
        assertEquals(64_050L * 1024L / 1000L, result.cumulativeEventTime1024)
    }

    @Test
    fun monotonicEventTimeKeepsExactDeltaInThe60To64SecondBand() {
        // A 62 s gap is past the conservative 60 s speed guard but BEFORE the true 64 s wrap,
        // so the event time has NOT aliased: the accumulator must keep the exact dTicks, not
        // substitute a coarser wall-clock estimate.
        val decoder = CscMeasurementDecoder()
        decoder.decode(wheelPacket(revs = 1000, time = 1000), circumference, receivedAtMs = 10_000L)
        // event time advanced exactly 62.0 s = 63488 ticks (< 65536, no 16-bit wrap).
        val dTicks = 62 * 1024
        val result = decoder.decode(
            wheelPacket(revs = 1001, time = 1000 + dTicks),
            circumference,
            receivedAtMs = 72_000L, // real gap 62 s: > 60 s guard, < 64 s wrap
        )
        assertEquals(dTicks.toLong(), result.cumulativeEventTime1024)
        assertNull("speed still suppressed by the conservative 60 s guard", result.speedKph)
    }

    @Test
    fun monotonicEventTimeEstimatesARebootGapAndStaysMonotonic() {
        // A reboot (revs jump backward past half-range) makes the event time ambiguous; the
        // accumulator advances by the wall-clock estimate for that gap and never goes backward.
        val decoder = CscMeasurementDecoder()
        decoder.decode(wheelPacket(revs = 1_000_000, time = 1000), circumference, receivedAtMs = 10_000L)
        val result = decoder.decode(wheelPacket(revs = 5, time = 2000), circumference, receivedAtMs = 15_000L)
        assertEquals(0L, result.wheelDeltaRevs) // reboot: no false advance
        assertEquals(5_000L * 1024L / 1000L, result.cumulativeEventTime1024) // wall-clock estimate
        assertTrue("accumulator stays monotonic across a reboot", result.cumulativeEventTime1024 >= 0L)
    }

    @Test
    fun monotonicEventTimeFreezesOnARebootWithNoWallClock() {
        // With no clock available the reboot gap cannot be estimated; the accumulator must
        // freeze (advance by 0) rather than go backward or invent time.
        val decoder = CscMeasurementDecoder()
        val baseline = decoder.decode(wheelPacket(revs = 1003, time = 5800), circumference)
        decoder.decode(wheelPacket(revs = 1004, time = 6300), circumference)
        val before = decoder.decode(wheelPacket(revs = 1005, time = 6800), circumference)
        val rebooted = decoder.decode(wheelPacket(revs = 5, time = 100), circumference)
        assertEquals(0L, rebooted.wheelDeltaRevs)
        assertEquals("frozen, not negative", before.cumulativeEventTime1024, rebooted.cumulativeEventTime1024)
        assertTrue(rebooted.cumulativeEventTime1024 >= baseline.cumulativeEventTime1024)
    }

    @Test
    fun monotonicEventTimeIsPerConnectionAndResetsToZero() {
        // Load-bearing offline-analysis contract: a reconnect builds a FRESH decoder, so the
        // accumulator restarts at 0 and a backward jump marks a connection-segment boundary.
        // This pins the per-instance (not static/companion) semantics: two independent decoders
        // each produce their own zero-based series, regardless of feed order or large inputs.
        val a = CscMeasurementDecoder()
        a.decode(wheelPacket(revs = 1000, time = 5000), circumference)
        val aSecond = a.decode(wheelPacket(revs = 1003, time = 5800), circumference)
        assertEquals("first decoder accumulated independently", 800L, aSecond.cumulativeEventTime1024)

        // A second decoder, fed large revs/time, must still start its own series at 0 — proving
        // the accumulator is not shared across instances.
        val b = CscMeasurementDecoder()
        val bFirst = b.decode(wheelPacket(revs = 9_000_000, time = 60000), circumference)
        assertEquals("fresh decoder restarts at 0 regardless of inputs", 0L, bFirst.cumulativeEventTime1024)
        val bSecond = b.decode(wheelPacket(revs = 9_000_005, time = 60500), circumference)
        assertEquals("second decoder accumulates from its own baseline", 500L, bSecond.cumulativeEventTime1024)

        // The first decoder is unaffected by the second's activity.
        val aThird = a.decode(wheelPacket(revs = 1004, time = 6300), circumference)
        assertEquals("first decoder unchanged by the second", 1300L, aThird.cumulativeEventTime1024)
    }

    @Test
    fun emptyAndTruncatedPacketsAreSafe() {
        val decoder = CscMeasurementDecoder()
        val empty = decoder.decode(ByteArray(0), circumference)
        assertNull(empty.speedKph)

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

package com.roundearth.bikecomputer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [CscBleDataSource.parseFirmwareRevision], the pure transform that turns
 * a Device Information Service Firmware Revision (0x2A26) value into a display string,
 * and [CscBleDataSource.mergeScanResult], the atomic scan-rebuild of a seen[] entry.
 *
 * The byte path itself (reading the characteristic) needs a real GATT, but the
 * null/empty/trim/decode logic and the firmware-preservation merge are pure and cheap
 * to cover here.
 */
class FirmwareRevisionParseTest {

    private fun parse(bytes: ByteArray?) = CscBleDataSource.parseFirmwareRevision(bytes)

    @Test
    fun nullBytesYieldNull() {
        assertNull(parse(null))
    }

    @Test
    fun emptyBytesYieldNull() {
        assertNull(parse(ByteArray(0)))
    }

    @Test
    fun whitespaceOnlyYieldsNull() {
        assertNull(parse(" \t\r\n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun nulOnlyYieldsNull() {
        assertNull(parse(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun normalRevisionIsReturnedVerbatim() {
        assertEquals("1.0 (build Jun  7 2026)", parse("1.0 (build Jun  7 2026)".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun trailingNulPaddingIsStripped() {
        val bytes = "1.0".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0, 0)
        assertEquals("1.0", parse(bytes))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("1.0", parse("  1.0\n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun interiorSpacesArePreserved() {
        // Only leading/trailing padding is trimmed; spaces inside the build string stay.
        val padded = byteArrayOf(0) + "1.0 (build x)".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        assertEquals("1.0 (build x)", parse(padded))
    }

    // --- mergeScanResult: the atomic scan-rebuild that must not lose a firmware revision ---

    private fun merge(prev: DiscoveredSensor?, connected: Boolean = false) =
        CscBleDataSource.mergeScanResult(prev, address = "AA:BB", name = "Bike Speed", rssi = -50, connected = connected)

    @Test
    fun firmwareRevisionSurvivesARescan() {
        // The race fix: a sensor whose firmware was already read keeps it when its
        // advertisement is re-seen (the scan callback rebuilds the entry via compute()).
        val withFw = DiscoveredSensor("AA:BB", "Bike Speed", -60, paired = false, connected = true, firmwareRevision = "1.0")
        assertEquals("1.0", merge(withFw).firmwareRevision)
    }

    @Test
    fun firstSightHasNoFirmwareRevision() {
        // No prior entry (first advertisement): nothing to carry forward.
        assertNull(merge(null).firmwareRevision)
    }

    @Test
    fun rescanRefreshesRssiAndConnectedButKeepsFirmware() {
        val prev = DiscoveredSensor("AA:BB", "Bike Speed", -90, paired = false, connected = false, firmwareRevision = "2.3")
        val next = merge(prev, connected = true)
        assertEquals(-50, next.rssi)        // fresh advertisement's RSSI
        assertEquals(true, next.connected)  // fresh connection state
        assertEquals("2.3", next.firmwareRevision)
    }
}

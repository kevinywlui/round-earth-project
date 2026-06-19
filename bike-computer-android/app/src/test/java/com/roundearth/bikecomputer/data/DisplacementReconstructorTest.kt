package com.roundearth.bikecomputer.data

import com.roundearth.bikecomputer.data.db.BacklogMinute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class DisplacementReconstructorTest {

    private var idc = 1L
    /** A backlog row at the given wall-clock minute with the given cumulative count. */
    private fun row(boot: Long, index: Long, minute: Long, cumRev: Long, circ: Double = 2.0) =
        BacklogMinute(
            id = idc++, sensorMac = "AA:BB", bootId = boot, recordIndex = index,
            uptimeSeconds = minute * 60, cumulativeRevolutions = cumRev,
            wallClockMillis = minute * 60_000L, wheelCircumferenceM = circ,
        )

    @Test
    fun dueNorthGoesAllNorth() {
        // boot 1: minute 0 baseline 0, minute 1 cumulative 10 → Δ10 rev × 2 m = 20 m, heading 0° = north.
        val rows = listOf(row(1, 0, 0, 0), row(1, 1, 1, 10))
        val (_, total) = DisplacementReconstructor.reconstruct(rows) { 0f }
        assertEquals(20.0, total.northMeters, 1e-9)
        assertEquals(0.0, total.eastMeters, 1e-9)
        assertEquals(20.0, total.knownDirectionMeters, 1e-9)
    }

    @Test
    fun dueEastGoesAllEast() {
        val rows = listOf(row(1, 0, 0, 0), row(1, 1, 1, 10))
        val (_, total) = DisplacementReconstructor.reconstruct(rows) { 90f }
        assertEquals(0.0, total.northMeters, 1e-9)
        assertEquals(20.0, total.eastMeters, 1e-9)
    }

    @Test
    fun fortyFiveDegreesSplitsEqually() {
        val rows = listOf(row(1, 0, 0, 0), row(1, 1, 1, 10))
        val (_, total) = DisplacementReconstructor.reconstruct(rows) { 45f }
        val expected = 20.0 / sqrt(2.0)
        assertEquals(expected, total.northMeters, 1e-9)
        assertEquals(expected, total.eastMeters, 1e-9)
    }

    @Test
    fun unknownHeadingIsUnknownDirectionNotNorthNotZero() {
        // A minute with real revolutions but no heading must NOT be counted as north (cos 0) or 0.
        val rows = listOf(row(1, 0, 0, 0), row(1, 1, 1, 10))
        val (perMinute, total) = DisplacementReconstructor.reconstruct(rows) { null }
        assertEquals("direction unknown → no north", 0.0, total.northMeters, 1e-9)
        assertEquals("direction unknown → no east", 0.0, total.eastMeters, 1e-9)
        assertEquals("but distance is still real", 20.0, total.unknownDirectionMeters, 1e-9)
        assertEquals(0.0, total.knownDirectionMeters, 1e-9)
        assertNull(perMinute.single().northMeters)
        assertNull(perMinute.single().eastMeters)
        assertEquals(20.0, perMinute.single().distanceMeters, 1e-9)
    }

    @Test
    fun naNHeadingIsTreatedAsUnknown() {
        val rows = listOf(row(1, 0, 0, 0), row(1, 1, 1, 10))
        val (_, total) = DisplacementReconstructor.reconstruct(rows) { Float.NaN }
        assertEquals(20.0, total.unknownDirectionMeters, 1e-9)
        assertEquals(0.0, total.knownDirectionMeters, 1e-9)
    }

    @Test
    fun doesNotSubtractAcrossBootBoundary() {
        // boot 1 ends at cumulative 110; boot 2 restarts at 5 then 12. The 110→5 step must NOT count
        // as negative distance, and only the within-boot deltas (10 and 7) contribute.
        val rows = listOf(
            row(1, 0, 0, 100), row(1, 1, 1, 110),   // boot 1: Δ10
            row(2, 2, 2, 5), row(2, 3, 3, 12),       // boot 2: Δ7
        )
        val (_, total) = DisplacementReconstructor.reconstruct(rows) { 0f }
        assertEquals((10 + 7) * 2.0, total.northMeters, 1e-9) // 34 m north, no spurious negative
        assertEquals((10 + 7) * 2.0, total.knownDirectionMeters, 1e-9)
    }

    @Test
    fun firstRowOfBootIsBaselineOnly() {
        // A lone first record of a boot establishes a baseline and contributes no displacement.
        val rows = listOf(row(1, 0, 5, 1000))
        val (perMinute, total) = DisplacementReconstructor.reconstruct(rows) { 0f }
        assertEquals(0, perMinute.size)
        assertEquals(0.0, total.northMeters, 1e-9)
    }

    @Test
    fun mixedKnownAndUnknownMinutes() {
        // minute 1 north (heading 0), minute 2 unknown, minute 3 east (heading 90).
        val rows = listOf(row(1, 0, 0, 0), row(1, 1, 1, 10), row(1, 2, 2, 20), row(1, 3, 3, 30))
        val headings = mapOf(1L to 0f, 3L to 90f) // minute 2 absent → unknown
        val (_, total) = DisplacementReconstructor.reconstruct(rows) { headings[it] }
        assertEquals(20.0, total.northMeters, 1e-9)            // minute 1
        assertEquals(20.0, total.eastMeters, 1e-9)            // minute 3
        assertEquals(20.0, total.unknownDirectionMeters, 1e-9) // minute 2
        assertEquals(40.0, total.knownDirectionMeters, 1e-9)
    }
}

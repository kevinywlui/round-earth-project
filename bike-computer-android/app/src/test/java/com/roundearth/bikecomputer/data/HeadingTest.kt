package com.roundearth.bikecomputer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure heading math in Heading.kt — the wrap-aware helpers and the
 * mounting-offset correction. NaN ("unknown heading") handling is the load-bearing case:
 * a missing reading must never silently become 0° and corrupt the northward reconstruction.
 */
class HeadingTest {

    private val eps = 1e-3f

    @Test
    fun trueFromMagneticAddsDeclinationAndWraps() {
        assertEquals(15f, trueFromMagnetic(10f, 5f), eps)
        assertEquals(5f, trueFromMagnetic(350f, 15f), eps)   // 365 -> 5
        assertEquals(350f, trueFromMagnetic(0f, -10f), eps)  // -10 -> 350
    }

    @Test
    fun angularDistanceIsWrapAware() {
        assertEquals(0.4f, angularDistance(359.8f, 0.2f), eps)
        assertEquals(180f, angularDistance(0f, 180f), eps)
        assertEquals(90f, angularDistance(45f, 315f), eps)
    }

    @Test
    fun signedAngleDeltaTakesTheShortWayAcrossTheWrap() {
        assertEquals(20f, signedAngleDelta(350f, 10f), eps)
        assertEquals(-20f, signedAngleDelta(10f, 350f), eps)
    }

    @Test
    fun mountingOffsetSubtractsAndNormalizes() {
        assertEquals(0f, applyMountingOffset(90f, 90f), eps)      // pointing "forward" -> 0
        assertEquals(350f, applyMountingOffset(10f, 20f), eps)    // wraps below 0
        assertEquals(90f, applyMountingOffset(450f, 0f), eps)     // raw above 360 normalizes
    }

    @Test
    fun mountingOffsetHandlesNegativeOffset() {
        assertEquals(30f, applyMountingOffset(10f, -20f), eps)
    }

    @Test
    fun mountingOffsetPreservesUnknownHeading() {
        assertTrue("NaN must stay unknown, not become 0", applyMountingOffset(Float.NaN, 45f).isNaN())
    }
}

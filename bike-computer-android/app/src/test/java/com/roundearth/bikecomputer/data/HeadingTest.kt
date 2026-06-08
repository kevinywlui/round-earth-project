package com.roundearth.bikecomputer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * The recorded trueHeadingDegrees stays "unknown" when either input is unknown. This
     * holds only because NaN propagates through `((NaN + d) % 360 + 360) % 360`; a future
     * normalization-helper rewrite that broke that would silently collapse every persisted
     * true heading to a real number near 0 (due north). These pin the invariant directly.
     */
    @Test
    fun trueFromMagneticPropagatesUnknownHeading() {
        assertTrue("unknown magnetic stays unknown", trueFromMagnetic(Float.NaN, 12f).isNaN())
        assertTrue("unknown declination stays unknown", trueFromMagnetic(90f, Float.NaN).isNaN())
    }

    private val eps1 = 1f

    /**
     * The heading ticker's emit gate. Sub-epsilon jitter is "settled" (no emit); larger moves
     * are not (emit). This is the live-state half of the NaN invariant: it is the gate that
     * decides whether a fresh bearing reaches the dashboard, so its NaN behavior is pinned
     * directly here, not just transitively through angularDistance.
     */
    @Test
    fun headingSettledIgnoresSubEpsilonJitterButNotRealMoves() {
        assertTrue("sub-degree jitter is settled", headingSettled(100f, 100.5f, 110f, 110.4f, eps1))
        assertFalse("a real magnetic move is not settled", headingSettled(100f, 130f, 110f, 110.2f, eps1))
        assertFalse("a real true move is not settled", headingSettled(100f, 100.2f, 110f, 140f, eps1))
    }

    @Test
    fun headingSettledForcesEmitAcrossUnknownTransitions() {
        // real -> NaN: the sensor dropped out; must emit so the dashboard shows "---".
        assertFalse("real to unknown must emit", headingSettled(100f, Float.NaN, 110f, Float.NaN, eps1))
        // NaN -> real: first reading after unknown must emit so a true bearing appears.
        assertFalse("unknown to real must emit", headingSettled(Float.NaN, 100f, Float.NaN, 110f, eps1))
        // NaN -> NaN: angularDistance(NaN, NaN) is NaN and NaN < eps is false, so "settled"
        // is false. The gate then copies NaN over NaN, leaving the state "unknown" regardless.
        assertFalse("unknown to unknown is not 'settled' (NaN propagates)", headingSettled(Float.NaN, Float.NaN, Float.NaN, Float.NaN, eps1))
    }
}

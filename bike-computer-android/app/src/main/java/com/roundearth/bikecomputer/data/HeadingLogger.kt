package com.roundearth.bikecomputer.data

import com.roundearth.bikecomputer.data.db.HeadingMinute
import com.roundearth.bikecomputer.data.db.HeadingMinuteDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Samples the phone compass once per [sampleIntervalMs] and writes ONE [HeadingMinute] per wall-clock
 * minute — the per-minute heading timeline that pairs with the wheel-revolution backlog to reconstruct
 * 2-D displacement (N = Σ Δrev·circ·cos θ, E = Σ Δrev·circ·sin θ) when per-revolution data is absent.
 *
 * Independent of BLE: it runs whenever the phone is collecting (owned by the foreground service via
 * [BikeApplication.startCollection]/[stopCollection]), because heading is a phone-side signal that
 * exists even with no sensor connected. The minute value is a CIRCULAR mean of the magnetic samples
 * (stored magnetic, so declination stays recoverable); true is derived with the current declination.
 *
 * Doze note: a plain minute timer is frozen with the screen off, so the foreground service holds a
 * wake lock while collecting (see CollectionService) to keep these samples flowing.
 */
class HeadingLogger(
    /** Mounting-offset-corrected MAGNETIC heading in degrees, or NaN when unknown. */
    private val magneticHeading: () -> Float,
    private val declinationDeg: () -> Float,
    /** Compass accuracy (SensorManager.SENSOR_STATUS_*); larger is better. */
    private val compassAccuracy: () -> Int,
    private val dao: HeadingMinuteDao,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sampleIntervalMs: Long = 2_000L,
) {
    private var job: Job? = null
    // Current minute + accumulator, held at class scope so stop() can flush the final partial minute
    // (otherwise the last minute of every ride is lost). Touched only from the single ticker coroutine
    // and stop(), which cancels it first, so there is no concurrent access.
    private var minute = 0L
    private var acc = Accumulator()

    /** Idempotent (guards on a live job) so a sticky service restart doesn't spawn a second ticker. */
    fun start() {
        if (job != null) return
        minute = nowMs() / 60_000L
        acc = Accumulator()
        job = scope.launch {
            while (isActive) {
                val curMinute = nowMs() / 60_000L
                if (curMinute != minute) {
                    dao.insert(acc.build(minute))
                    minute = curMinute
                    acc = Accumulator()
                }
                acc.sample(magneticHeading(), compassAccuracy())
                delay(sampleIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Persist the in-progress minute so the final minute of a ride isn't dropped (fire-and-forget
        // on the still-live scope; INSERT OR IGNORE makes it a no-op if that minute later reappears).
        val partial = acc
        val m = minute
        if (partial.count() > 0) scope.launch { dao.insert(partial.build(m)) }
    }

    /**
     * Folds a minute's compass samples into a [HeadingMinute]. Pure and self-contained so the
     * aggregation rules (circular mean, NaN-skipping, worst-accuracy, NULL for an empty minute) are
     * unit-testable without the time loop. [declinationDeg]/build are captured at flush time.
     */
    inner class Accumulator {
        private val mean = CircularMean()
        private var worstAccuracy = Int.MAX_VALUE

        /** Valid (non-NaN) samples folded so far — lets stop() skip flushing an empty final minute. */
        fun count(): Int = mean.count

        fun sample(magnetic: Float, accuracy: Int) {
            if (magnetic.isNaN()) return // skip unknown samples; don't let one poison the minute
            mean.add(magnetic)
            if (accuracy < worstAccuracy) worstAccuracy = accuracy
        }

        fun build(minuteEpoch: Long): HeadingMinute {
            val ts = minuteEpoch * 60_000L
            if (mean.count == 0) {
                // Phone ran this minute but the compass had no valid fix → explicit NULL row (never 0).
                return HeadingMinute(0, minuteEpoch, ts, null, null, 0, -1)
            }
            val mag = mean.mean()
            return HeadingMinute(
                minuteEpoch = minuteEpoch,
                timestampMillis = ts,
                headingDegrees = mag,
                trueHeadingDegrees = trueFromMagnetic(mag, declinationDeg()),
                sampleCount = mean.count,
                compassAccuracy = if (worstAccuracy == Int.MAX_VALUE) -1 else worstAccuracy,
            )
        }
    }
}

package com.roundearth.bikecomputer.data

import com.roundearth.bikecomputer.data.db.HeadingMinute
import com.roundearth.bikecomputer.data.db.HeadingMinuteDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A no-op DAO so the pure [HeadingLogger.Accumulator] can be built without Room. */
private object NoopHeadingDao : HeadingMinuteDao {
    override suspend fun insert(row: HeadingMinute) {}
    override suspend fun forMinute(minuteEpoch: Long): HeadingMinute? = null
    override suspend fun all(): List<HeadingMinute> = emptyList()
    override suspend fun deleteAll() {}
}

/** Pure-logic tests for the per-minute backlog parser and the circular-mean heading aggregator. */
class BacklogAndHeadingTest {

    private fun logger(declination: Float = 0f) = HeadingLogger(
        magneticHeading = { Float.NaN }, declinationDeg = { declination },
        compassAccuracy = { 3 }, dao = NoopHeadingDao, scope = CoroutineScope(SupervisorJob()),
    )

    @Test
    fun accumulatorBuildsCircularMeanAndDerivesTrue() {
        val acc = logger(declination = 10f).Accumulator()
        acc.sample(350f, 3); acc.sample(10f, 2)
        val row = acc.build(5L)
        assertTrue("magnetic mean ~0", angularDistance(row.headingDegrees!!, 0f) < 0.01f)
        assertTrue("true = magnetic + 10° declination", angularDistance(row.trueHeadingDegrees!!, 10f) < 0.01f)
        assertEquals(2, row.sampleCount)
        assertEquals("worst (min) accuracy of 3 and 2", 2, row.compassAccuracy)
        assertEquals(5L, row.minuteEpoch)
        assertEquals(5L * 60_000, row.timestampMillis)
    }

    @Test
    fun accumulatorEmptyMinuteIsNullRowNeverZero() {
        val row = logger().Accumulator().build(7L)
        assertNull("unknown heading is NULL, never 0", row.headingDegrees)
        assertNull(row.trueHeadingDegrees)
        assertEquals(0, row.sampleCount)
        assertEquals(-1, row.compassAccuracy)
    }

    @Test
    fun accumulatorSkipsNaNSamples() {
        val acc = logger().Accumulator()
        acc.sample(Float.NaN, 3); acc.sample(90f, 1); acc.sample(Float.NaN, 0)
        val row = acc.build(1L)
        assertEquals("only the one valid sample counts", 1, row.sampleCount)
        assertTrue(angularDistance(row.headingDegrees!!, 90f) < 0.01f)
        assertEquals("NaN samples don't drag accuracy down", 1, row.compassAccuracy)
    }

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun record(bootId: Long, idx: Long, uptime: Long, revs: Long): ByteArray =
        le32(bootId) + le32(idx) + le32(uptime) + le32(revs)

    @Test
    fun parsesRecordRoundTrip() {
        val r = BacklogRecordParser.parseRecord(record(7, 42, 1234, 5000))!!
        assertEquals(7L, r.bootId)
        assertEquals(42L, r.recordIndex)
        assertEquals(1234L, r.uptimeSeconds)
        assertEquals(5000L, r.cumulativeRevolutions)
    }

    @Test
    fun terminatorAndEmptySlotParseToNull() {
        assertNull("terminator is end-of-stream", BacklogRecordParser.parseRecord(record(7, 0xFFFFFFFFL, 0, 0)))
        assertTrue(BacklogRecordParser.isTerminator(record(7, 0xFFFFFFFFL, 0, 0)))
        assertNull("all-zero slot is empty", BacklogRecordParser.parseRecord(record(0, 0, 0, 0)))
    }

    @Test
    fun handlesFullU32WithoutSignExtension() {
        // ~4.29e9 revolutions must read as a positive Long, not a negative Int.
        val r = BacklogRecordParser.parseRecord(record(0xFFFFFFF0L, 10, 0, 0xFFFFFFF0L))!!
        assertEquals(0xFFFFFFF0L, r.cumulativeRevolutions)
        assertEquals(0xFFFFFFF0L, r.bootId)
    }

    @Test
    fun parsesInfoBlock() {
        val info = BacklogRecordParser.parseInfo(
            le32(7) + le32(3600) + le32(100) + le32(280) + le32(2)
        )!!
        assertEquals(7L, info.bootId)
        assertEquals(3600L, info.currentUptimeSeconds)
        assertEquals(100L, info.oldestIndex)
        assertEquals(280L, info.newestIndex)
        assertEquals(2L, info.overflow)
    }

    @Test
    fun wallClockIsPreciseForCurrentBootAndBoundedForPrior() {
        val info = BacklogRecordParser.Info(bootId = 7, currentUptimeSeconds = 3600, oldestIndex = 0, newestIndex = 10, overflow = 0)
        val anchor = 1_000_000_000_000L // connect-time wall clock
        // Current boot, logged 600 s before "now" (uptime 3000 vs 3600): wallclock = anchor − 600 s.
        val cur = BacklogRecordParser.Record(bootId = 7, recordIndex = 5, uptimeSeconds = 3000, cumulativeRevolutions = 1)
        assertEquals(anchor - 600_000L, BacklogRecordParser.wallClockMillis(cur, info, anchor))
        // Prior boot: placed at the current boot's start (anchor − currentUptime), a lower bound.
        val prior = BacklogRecordParser.Record(bootId = 6, recordIndex = 1, uptimeSeconds = 50, cumulativeRevolutions = 1)
        assertEquals(anchor - 3_600_000L, BacklogRecordParser.wallClockMillis(prior, info, anchor))
    }

    @Test
    fun circularMeanWrapsCorrectly() {
        val m = CircularMean()
        m.add(350f); m.add(10f)
        // Arithmetic mean would be 170° (south!); the circular mean must be ~0°.
        val mean = m.mean()
        assertTrue("expected ~0/360, got $mean", angularDistance(mean, 0f) < 0.001f)
        assertEquals(2, m.count)
    }

    @Test
    fun circularMeanSkipsNaNAndEmptyIsNaN() {
        val m = CircularMean()
        assertTrue("empty mean is NaN", m.mean().isNaN())
        m.add(Float.NaN); m.add(90f); m.add(Float.NaN)
        assertEquals(1, m.count)
        assertTrue("one valid sample of 90°", angularDistance(m.mean(), 90f) < 0.001f)
    }
}

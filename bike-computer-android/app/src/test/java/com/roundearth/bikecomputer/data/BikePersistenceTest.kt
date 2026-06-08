package com.roundearth.bikecomputer.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundearth.bikecomputer.data.db.BikeDatabase
import com.roundearth.bikecomputer.data.db.RevolutionEvent
import com.roundearth.bikecomputer.data.db.RevolutionEventDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tier-1 persistence tests: the Room path in [BikeRepository]/[RevolutionEventDao]
 * exercised against a real in-memory Room DB under a Robolectric runtime.
 *
 * The headline regression lock is NaN-heading -> NULL (the shipped recording-crash
 * fix): a bound Float.NaN would violate a NOT NULL column, so the repository maps it
 * to null and the column is nullable. These tests assert that contract end-to-end
 * (insert via the repo's collect loop, read back NULL, export an empty CSV field)
 * plus session-resume seeding, the empty-session 0.0 coalesce, and the per-row
 * distance SUM(delta * circumference).
 *
 * Determinism note: Room runs its suspend DAO calls on its own real background
 * executors. We deliberately do NOT rebind them to a TestCoroutineScheduler — that
 * deadlocks, since Room's open/query path posts to the executor and then blocks
 * waiting for it while virtual time only advances when a coroutine drives it. So we
 * run on a real dispatcher under runBlocking and, for the two tests that drive the
 * repository's collect loop, await the observable result with a bounded [awaitRow]
 * poll rather than advanceUntilIdle. The pure-DAO tests simply suspend on the real
 * Room call. No allowMainThreadQueries is needed (all access is via suspend DAO).
 */
@RunWith(RobolectricTestRunner::class)
class BikePersistenceTest {

    private lateinit var db: BikeDatabase
    private lateinit var dao: RevolutionEventDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BikeDatabase::class.java,
        ).build()
        dao = db.revolutionEventDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun reading(
        cumulativeRevolutions: Long = 100,
        deltaRevolutions: Long = 1,
        wheelCircumferenceM: Double = 2.0,
        headingDegrees: Float = 90f,
        trueHeadingDegrees: Float = 95f,
        timestampMillis: Long = 1_000L,
    ) = WheelRevolutionReading(
        timestampMillis = timestampMillis,
        cumulativeRevolutions = cumulativeRevolutions,
        deltaRevolutions = deltaRevolutions,
        sensorEventTime1024 = 512,
        cumulativeEventTime1024 = 512L,
        wheelCircumferenceM = wheelCircumferenceM,
        headingDegrees = headingDegrees,
        trueHeadingDegrees = trueHeadingDegrees,
    )

    private fun event(
        sessionId: Long,
        deltaRevolutions: Long,
        wheelCircumferenceM: Double,
        cumulativeRevolutions: Long = deltaRevolutions,
        cumulativeEventTime1024: Long = 0L,
        sensorEventTime1024: Int = 0,
        timestampMillis: Long = sessionId,
        headingDegrees: Float? = null,
        trueHeadingDegrees: Float? = null,
    ) = RevolutionEvent(
        sessionId = sessionId,
        timestampMillis = timestampMillis,
        cumulativeRevolutions = cumulativeRevolutions,
        deltaRevolutions = deltaRevolutions,
        sensorEventTime1024 = sensorEventTime1024,
        cumulativeEventTime1024 = cumulativeEventTime1024,
        wheelCircumferenceM = wheelCircumferenceM,
        headingDegrees = headingDegrees,
        trueHeadingDegrees = trueHeadingDegrees,
    )

    private fun repo(source: FakeBikeDataSource, scope: CoroutineScope): BikeRepository =
        BikeRepository(
            source = source,
            prefs = PreferencesStore(ApplicationProvider.getApplicationContext()),
            dao = dao,
            scope = scope,
        )

    /** Polls [block] until it returns non-null or a short real-time budget elapses. */
    private suspend fun <T> awaitNonNull(block: suspend () -> T?): T =
        withTimeout(5_000) {
            while (true) {
                block()?.let { return@withTimeout it }
                delay(5)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    // --- Tier 1.1: NaN heading -> NULL, the shipped recording-crash regression lock ---

    @Test
    fun nanHeadingIsPersistedAsNullNotThrow() = runBlocking {
        val source = FakeBikeDataSource()
        repo(source, this).start()

        // A NaN heading must round-trip to a NULL column without a NOT NULL constraint crash.
        // (Emit-with-retry until the collector has subscribed to the no-replay SharedFlow.)
        awaitNonNull {
            source.readings.emit(
                reading(headingDegrees = Float.NaN, trueHeadingDegrees = Float.NaN)
            )
            dao.lastEvent()
        }

        val row = dao.lastEvent()!!
        assertNull("NaN magnetic heading must persist as NULL", row.headingDegrees)
        assertNull("NaN true heading must persist as NULL", row.trueHeadingDegrees)
        coroutineContext.cancelChildren()
    }

    @Test
    fun knownHeadingIsPersistedAsItsValue() = runBlocking {
        val source = FakeBikeDataSource()
        repo(source, this).start()

        val row = awaitNonNull {
            source.readings.emit(reading(headingDegrees = 90f, trueHeadingDegrees = 95f))
            dao.lastEvent()
        }
        assertEquals(90f, row.headingDegrees)
        assertEquals(95f, row.trueHeadingDegrees)
        coroutineContext.cancelChildren()
    }

    @Test
    fun nanHeadingExportsAsEmptyCsvFieldNotNaN() = runBlocking {
        // Persist one unknown-heading (NULL) event, then export.
        dao.insert(event(sessionId = 1L, deltaRevolutions = 1, wheelCircumferenceM = 2.0))
        val repo = repo(FakeBikeDataSource(), this)

        val csv = StringBuilder()
        repo.exportCsvTo(csv)

        val dataLine = csv.lines().first { it.startsWith("1,") }
        // Last two fields (heading, true_heading) must be empty, never "NaN" or "0".
        assertTrue("unknown heading is an empty trailing field, got: $dataLine", dataLine.endsWith(",,"))
        assertTrue("export must not write NaN", !csv.contains("NaN"))
    }

    // --- Tier 1.2: session resume seeds the odometer from the prior ride's distance ---

    @Test
    fun recentPriorSessionResumesAndSeedsItsDistance() = runBlocking {
        // A prior event within the resume window: 10 revs * 2.0 m = 20 m of distance.
        val recent = System.currentTimeMillis() - 1_000L
        dao.insert(
            event(
                sessionId = 5_000L,
                deltaRevolutions = 10,
                wheelCircumferenceM = 2.0,
                timestampMillis = recent,
            )
        )
        val source = FakeBikeDataSource()
        repo(source, this).start()

        // The resumed session's accumulated distance is fed back as the odometer seed.
        val seeded = awaitNonNull { source.seededMeters }
        assertEquals(20.0, seeded, 1e-9)

        // A newly recorded event continues under the resumed (prior) session id. Poll on the NEW
        // row specifically (cumulativeRevolutions=100, which the seeded prior row's 10 lacks) so the
        // resume-continuity assertion can only pass once the fresh emission is actually inserted —
        // not be satisfied by the pre-existing seeded row that already carries sessionId 5000.
        val row = awaitNonNull {
            source.readings.emit(reading(timestampMillis = System.currentTimeMillis()))
            dao.lastEvent()?.takeIf { it.cumulativeRevolutions == 100L }
        }
        assertEquals(5_000L, row.sessionId)
        coroutineContext.cancelChildren()
    }

    @Test
    fun freshSessionSeedsZeroWhenNoPriorEvents() = runBlocking {
        // Empty DB: sessionDistanceMeters returns NULL, coalesced to 0.0 for the seed.
        val source = FakeBikeDataSource()
        repo(source, this).start()

        val seeded = awaitNonNull { source.seededMeters }
        assertEquals(0.0, seeded, 1e-9)
        coroutineContext.cancelChildren()
    }

    @Test
    fun emptySessionDistanceCoalescesToZero() = runBlocking {
        // sessionDistanceMeters of a never-recorded session is NULL at the DAO layer.
        assertNull(dao.sessionDistanceMeters(999L))
        // BikeRepository.start coalesces that NULL to 0.0 (?: 0.0) before seeding.
        assertEquals(0.0, dao.sessionDistanceMeters(999L) ?: 0.0, 1e-9)
    }

    // --- Tier 1.3: distance is the per-row SUM(delta * circumference), not (sum d)*c ---

    @Test
    fun distanceSumsEachRowsDeltaTimesItsOwnCircumference() = runBlocking {
        // Two rows in one session with DIFFERENT circumferences: the only correct total is
        // d1*c1 + d2*c2 = 3*2.0 + 4*2.5 = 16.0. A (d1+d2)*c collapse would give a wrong 14 or 17.5.
        dao.insert(event(sessionId = 7L, deltaRevolutions = 3, wheelCircumferenceM = 2.0))
        dao.insert(event(sessionId = 7L, deltaRevolutions = 4, wheelCircumferenceM = 2.5))
        // A zero-delta row contributes 0 trivially (documents reboot/baseline intent only).
        dao.insert(event(sessionId = 7L, deltaRevolutions = 0, wheelCircumferenceM = 9.9))

        assertEquals(16.0, dao.sessionDistanceMeters(7L)!!, 1e-9)
    }

    @Test
    fun highMagnitudeRowRoundTripsWithoutTruncation() = runBlocking {
        // Realistic long-lived-sensor steady state: a cumulative count in the upper u32 band
        // and an event time near the 16-bit ceiling must round-trip without Int truncation.
        val bigRevs = 4_294_967_290L            // just below 0xFFFFFFFF
        val ceilingEventTime = 65_535           // 16-bit max
        dao.insert(
            event(
                sessionId = 8L,
                deltaRevolutions = 1,
                wheelCircumferenceM = 2.0,
                cumulativeRevolutions = bigRevs,
                sensorEventTime1024 = ceilingEventTime,
                cumulativeEventTime1024 = 9_000_000_000L,
            )
        )

        val row = dao.getPageAfter(0L, 10).single()
        assertEquals(bigRevs, row.cumulativeRevolutions)
        assertEquals(ceilingEventTime, row.sensorEventTime1024)
        assertEquals(9_000_000_000L, row.cumulativeEventTime1024)
    }
}

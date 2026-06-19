package com.roundearth.bikecomputer.data

import android.content.Context
import android.location.Location
import androidx.room.Room
import com.roundearth.bikecomputer.data.LocationLogger.Companion.toFix
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.roundearth.bikecomputer.data.db.BacklogMinute
import com.roundearth.bikecomputer.data.db.BikeDatabase
import com.roundearth.bikecomputer.data.db.GpsFix
import com.roundearth.bikecomputer.data.db.HeadingMinute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationAndTimelineDaoTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-test.db"

    @Before fun clean() { ctx.deleteDatabase(dbName) }
    @After fun cleanup() { ctx.deleteDatabase(dbName) }

    /**
     * Builds a real on-disk v7 database (just revolution_events, the v7 schema) with one row, then
     * opens it through Room with MIGRATION_7_8. Accessing a DAO forces Room to run the migration and
     * then VALIDATE the resulting schema against the entity definitions — so a mismatch in the hand-
     * written CREATE TABLE / index DDL throws here. Also asserts the existing ride row survives.
     */
    @Test
    fun migration7to8_preservesExistingDataAndCreatesNewTables() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `revolution_events` (" +
                                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`sessionId` INTEGER NOT NULL, `timestampMillis` INTEGER NOT NULL, " +
                                "`cumulativeRevolutions` INTEGER NOT NULL, `deltaRevolutions` INTEGER NOT NULL, " +
                                "`sensorEventTime1024` INTEGER NOT NULL, `cumulativeEventTime1024` INTEGER NOT NULL, " +
                                "`wheelCircumferenceM` REAL NOT NULL, `headingDegrees` REAL, `trueHeadingDegrees` REAL)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_revolution_events_sessionId` " +
                                "ON `revolution_events` (`sessionId`)"
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) {}
                }).build()
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO revolution_events (sessionId, timestampMillis, cumulativeRevolutions, " +
                    "deltaRevolutions, sensorEventTime1024, cumulativeEventTime1024, wheelCircumferenceM, " +
                    "headingDegrees, trueHeadingDegrees) VALUES (1, 1000, 5, 5, 100, 100, 2.0, NULL, NULL)"
            )
        }

        val room = Room.databaseBuilder(ctx, BikeDatabase::class.java, dbName)
            .addMigrations(BikeDatabase.MIGRATION_7_8)
            .build()
        runBlocking {
            // Existing ride preserved across the additive migration (NOT wiped).
            assertNotNull("pre-existing row must survive 7→8", room.revolutionEventDao().lastEvent())
            // New tables exist and are usable (proves the migration DDL matches Room's expectation).
            room.backlogMinuteDao().insert(
                BacklogMinute(0, "AA:BB", 1, 0, 0, 100, 1000, 2.0)
            )
            room.headingMinuteDao().insert(HeadingMinute(0, 10, 600_000, 12f, 15f, 240, 3))
            room.gpsFixDao().insert(GpsFix(0, 1000, 37.0, -122.0, 5f, null, null, null))
            assertEquals(1, room.gpsFixDao().count())
        }
        room.close()
    }

    @Test
    fun backlogInsertIsIdempotentOnSensorMacAndRecordIndex() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(ctx, BikeDatabase::class.java).build()
        val dao = room.backlogMinuteDao()
        // Same (sensorMac, recordIndex) streamed twice (e.g. re-replay on reconnect) → one row.
        dao.insert(BacklogMinute(0, "AA:BB", 1, 7, 60, 100, 1000, 2.0))
        dao.insert(BacklogMinute(0, "AA:BB", 1, 7, 60, 100, 1000, 2.0))
        assertEquals(7L, dao.maxRecordIndex("AA:BB"))
        // A different sensor with the same recordIndex is NOT a duplicate.
        dao.insert(BacklogMinute(0, "CC:DD", 1, 7, 60, 50, 1000, 2.0))
        // Span distance for one boot: cumulative 100 → 140 over the boot = 40 rev × 2 m = 80 m.
        dao.insert(BacklogMinute(0, "AA:BB", 1, 8, 120, 140, 1060, 2.0))
        assertEquals(80.0, dao.bootDistanceMeters("AA:BB", 1)!!, 1e-9)
        room.close()
    }

    @Test
    fun headingMinuteInsertIsIdempotentOnMinute() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(ctx, BikeDatabase::class.java).build()
        val dao = room.headingMinuteDao()
        dao.insert(HeadingMinute(0, 100, 6_000_000, 10f, 12f, 50, 3))
        dao.insert(HeadingMinute(0, 100, 6_000_000, 99f, 99f, 1, 0)) // same minute → ignored
        val row = dao.forMinute(100)!!
        assertEquals("first write wins (IGNORE)", 10f, row.headingDegrees!!, 1e-6f)
        room.close()
    }

    @Test
    fun locationToFixMapsPresentFieldsAndNullsAbsentOnes() {
        val loc = Location("gps").apply { latitude = 37.5; longitude = -122.3; accuracy = 8f }
        val fix = loc.toFix(timestampMillis = 12345L)
        assertEquals(12345L, fix.timestampMillis)
        assertEquals(37.5, fix.latitude, 1e-9)
        assertEquals(-122.3, fix.longitude, 1e-9)
        assertEquals(8f, fix.accuracyMeters!!, 1e-6f)
        // altitude / bearing / speed were never set → null, not 0.
        org.junit.Assert.assertNull(fix.altitudeMeters)
        org.junit.Assert.assertNull(fix.bearingDegrees)
        org.junit.Assert.assertNull(fix.speedMps)
    }

    @Test
    fun locationWithoutAccuracyMapsToNullNotNaN() {
        // A fix lacking accuracy must map to NULL (a Float.NaN would coerce to NULL and crash the
        // NOT NULL column the old schema had); store + read back to prove it inserts cleanly.
        val loc = Location("gps").apply { latitude = 1.0; longitude = 2.0 } // no accuracy set
        val fix = loc.toFix(timestampMillis = 1L)
        org.junit.Assert.assertNull(fix.accuracyMeters)
        runBlocking {
            val room = Room.inMemoryDatabaseBuilder(ctx, BikeDatabase::class.java).build()
            room.gpsFixDao().insert(fix) // must not throw
            assertEquals(1, room.gpsFixDao().count())
            room.close()
        }
    }

    @Test
    fun headingMinuteStoresNullForUnknown() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(ctx, BikeDatabase::class.java).build()
        val dao = room.headingMinuteDao()
        dao.insert(HeadingMinute(0, 200, 12_000_000, null, null, 0, -1))
        val row = dao.forMinute(200)!!
        org.junit.Assert.assertNull("unknown heading is NULL, never 0", row.headingDegrees)
        org.junit.Assert.assertNull(row.trueHeadingDegrees)
        room.close()
    }
}

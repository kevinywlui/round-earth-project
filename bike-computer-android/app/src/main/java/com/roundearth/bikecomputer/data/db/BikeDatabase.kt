package com.roundearth.bikecomputer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RevolutionEvent::class,
        BacklogMinute::class,
        HeadingMinute::class,
        GpsFix::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class BikeDatabase : RoomDatabase() {
    abstract fun revolutionEventDao(): RevolutionEventDao
    abstract fun backlogMinuteDao(): BacklogMinuteDao
    abstract fun headingMinuteDao(): HeadingMinuteDao
    abstract fun gpsFixDao(): GpsFixDao

    companion object {
        @Volatile private var instance: BikeDatabase? = null

        /**
         * 7 → 8 is purely ADDITIVE: it creates the three new timeline tables and touches no existing
         * data. Unlike the destructive fallback policy used for incompatible schema changes, a new
         * table must NOT wipe recorded rides — the whole point of these tables is durable history. The
         * DDL is hand-matched to what Room generates for the entities (column names/affinities/null-
         * ability, AUTOINCREMENT PK, and the Room-style `index_<table>_<cols>` index names) so the
         * runtime schema-identity check passes.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `backlog_minutes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sensorMac` TEXT NOT NULL, " +
                        "`bootId` INTEGER NOT NULL, " +
                        "`recordIndex` INTEGER NOT NULL, " +
                        "`uptimeSeconds` INTEGER NOT NULL, " +
                        "`cumulativeRevolutions` INTEGER NOT NULL, " +
                        "`wallClockMillis` INTEGER NOT NULL, " +
                        "`wheelCircumferenceM` REAL NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_backlog_minutes_sensorMac_recordIndex` " +
                        "ON `backlog_minutes` (`sensorMac`, `recordIndex`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `heading_minutes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`minuteEpoch` INTEGER NOT NULL, " +
                        "`timestampMillis` INTEGER NOT NULL, " +
                        "`headingDegrees` REAL, " +
                        "`trueHeadingDegrees` REAL, " +
                        "`sampleCount` INTEGER NOT NULL, " +
                        "`compassAccuracy` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_heading_minutes_minuteEpoch` " +
                        "ON `heading_minutes` (`minuteEpoch`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gps_fixes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestampMillis` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`accuracyMeters` REAL, " +
                        "`altitudeMeters` REAL, " +
                        "`bearingDegrees` REAL, " +
                        "`speedMps` REAL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_gps_fixes_timestampMillis` " +
                        "ON `gps_fixes` (`timestampMillis`)"
                )
            }
        }

        // A real migration preserves existing rides on the additive 7→8 bump; destructive fallback
        // remains the last resort for any future INCOMPATIBLE change (the single-user policy).
        fun get(context: Context): BikeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BikeDatabase::class.java,
                "bike.db",
            ).addMigrations(MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}

package com.roundearth.bikecomputer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RevolutionEvent::class], version = 5, exportSchema = false)
abstract class BikeDatabase : RoomDatabase() {
    abstract fun revolutionEventDao(): RevolutionEventDao

    companion object {
        @Volatile private var instance: BikeDatabase? = null

        // v2: add headingDegrees so northward distance can be reconstructed.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE revolution_events ADD COLUMN headingDegrees REAL NOT NULL DEFAULT 0"
                )
            }
        }

        // v3: add trueHeadingDegrees (magnetic + declination) alongside magnetic.
        // Backfill legacy rows from headingDegrees (declination unknown for old
        // data, so assume 0 — true == magnetic — rather than a bogus 0° true).
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE revolution_events ADD COLUMN trueHeadingDegrees REAL NOT NULL DEFAULT 0"
                )
                db.execSQL("UPDATE revolution_events SET trueHeadingDegrees = headingDegrees")
            }
        }

        // v4: add deltaRevolutions (reboot/rollover-safe per-event advance) so session
        // totals and distance reconstruction stay correct across sensor counter resets.
        // Legacy rows default to 0 (their per-event delta is unknown after the fact).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE revolution_events ADD COLUMN deltaRevolutions INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v5: back-fill deltaRevolutions for rows written before v4 (which left them 0,
        // so those sessions' totals and distance silently undercounted). Each row's delta
        // is the cumulative advance over its predecessor in the same session; a backward
        // jump (sensor reboot) or the first row of a session clamps to 0 — exactly the
        // live decoder's rule. Idempotent: rows already carrying a correct live delta
        // recompute to the same value, so this is safe to run over a mixed table.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE revolution_events
                    SET deltaRevolutions = MAX(0, cumulativeRevolutions - (
                        SELECT prev.cumulativeRevolutions
                        FROM revolution_events AS prev
                        WHERE prev.sessionId = revolution_events.sessionId
                          AND prev.id < revolution_events.id
                        ORDER BY prev.id DESC
                        LIMIT 1
                    ))
                    WHERE EXISTS (
                        SELECT 1 FROM revolution_events AS prev
                        WHERE prev.sessionId = revolution_events.sessionId
                          AND prev.id < revolution_events.id
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): BikeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BikeDatabase::class.java,
                "bike.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { instance = it }
        }
    }
}

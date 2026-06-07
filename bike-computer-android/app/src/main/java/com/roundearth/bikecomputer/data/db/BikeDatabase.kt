package com.roundearth.bikecomputer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RevolutionEvent::class], version = 3, exportSchema = false)
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
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE revolution_events ADD COLUMN trueHeadingDegrees REAL NOT NULL DEFAULT 0"
                )
            }
        }

        fun get(context: Context): BikeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BikeDatabase::class.java,
                "bike.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}

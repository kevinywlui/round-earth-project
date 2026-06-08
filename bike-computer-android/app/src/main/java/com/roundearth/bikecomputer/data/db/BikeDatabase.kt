package com.roundearth.bikecomputer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RevolutionEvent::class], version = 7, exportSchema = false)
abstract class BikeDatabase : RoomDatabase() {
    abstract fun revolutionEventDao(): RevolutionEventDao

    companion object {
        @Volatile private var instance: BikeDatabase? = null

        // No hand-written migrations: this is a single-user app, so a schema change just
        // recreates the table and drops old rides. Bump the @Database version above on any
        // schema change to trigger the wipe.
        fun get(context: Context): BikeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BikeDatabase::class.java,
                "bike.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}

package com.roundearth.bikecomputer

import android.app.Application
import com.roundearth.bikecomputer.data.BikeRepository
import com.roundearth.bikecomputer.data.PreferencesStore
import com.roundearth.bikecomputer.data.SimulatedBikeDataSource

class BikeApplication : Application() {
    val prefs: PreferencesStore by lazy { PreferencesStore(this) }
    val repository: BikeRepository by lazy { BikeRepository(SimulatedBikeDataSource(), prefs) }
}

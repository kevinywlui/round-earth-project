package com.roundearth.bikecomputer

import android.app.Application
import android.os.Build
import com.roundearth.bikecomputer.data.BikeDataSource
import com.roundearth.bikecomputer.data.BikeRepository
import com.roundearth.bikecomputer.data.CscBleDataSource
import com.roundearth.bikecomputer.data.HeadingProvider
import com.roundearth.bikecomputer.data.PreferencesStore
import com.roundearth.bikecomputer.data.SimulatedBikeDataSource
import com.roundearth.bikecomputer.data.db.BikeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BikeApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob())

    val prefs: PreferencesStore by lazy { PreferencesStore(this) }

    // Latest wheel circumference, kept hot so the BLE source can read it synchronously.
    @Volatile private var circumferenceM: Double = PreferencesStore.DEFAULT_CIRCUMFERENCE

    private val headingProvider: HeadingProvider by lazy { HeadingProvider(this) }

    /** True when the real BLE source is in use (and BLE runtime permissions are needed). */
    val usesBle: Boolean by lazy { !isEmulator() }

    val repository: BikeRepository by lazy {
        val db = BikeDatabase.get(this)
        // Emulators have no BLE radio — fall back to simulated data so the app is
        // usable without hardware. Real devices use the CSC sensor.
        val source: BikeDataSource = if (isEmulator()) {
            SimulatedBikeDataSource { circumferenceM }
        } else {
            CscBleDataSource(this, { circumferenceM }, { headingProvider.degrees })
        }
        BikeRepository(source, prefs, db.revolutionEventDao(), appScope)
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.MODEL.contains("emulator", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")

    override fun onCreate() {
        super.onCreate()
        if (!isEmulator()) headingProvider.start()
        appScope.launch {
            prefs.wheelCircumferenceMeters.collect { circumferenceM = it }
        }
    }
}

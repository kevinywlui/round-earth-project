package com.roundearth.bikecomputer

import android.app.Application
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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

    // Latest magnetic declination, kept hot so the data source can read it synchronously.
    @Volatile private var declinationDeg: Float = PreferencesStore.DEFAULT_DECLINATION.toFloat()

    private val headingProvider: HeadingProvider by lazy { HeadingProvider(this) }

    /** True when the real BLE source is in use (and BLE runtime permissions are needed). */
    val usesBle: Boolean by lazy { !isEmulator() }

    /** The real BLE source when on hardware, else null (emulator uses simulated data). */
    val bleSource: CscBleDataSource? by lazy {
        if (isEmulator()) null
        else CscBleDataSource(
            context = this,
            wheelCircumferenceM = { circumferenceM },
            heading = { headingProvider.degrees },
            declination = { declinationDeg },
            pairedSensors = prefs.pairedSensors,
        )
    }

    val repository: BikeRepository by lazy {
        val db = BikeDatabase.get(this)
        // Emulators have no BLE radio — fall back to simulated data so the app is
        // usable without hardware. Real devices use the CSC sensor.
        val source: BikeDataSource = bleSource ?: SimulatedBikeDataSource({ circumferenceM }, { declinationDeg })
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
        appScope.launch {
            prefs.magneticDeclinationDeg.collect { declinationDeg = it.toFloat() }
        }
        // Stop scanning/connections in the background (battery; Android throttles a
        // long-running low-latency scan) and resume on return to the foreground. Only
        // resumes if recording was already started, so it never pre-empts the
        // permission request on first launch.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                repository.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                repository.onBackground()
            }
        })
    }
}

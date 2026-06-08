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
import com.roundearth.bikecomputer.data.applyMountingOffset
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

    // Latest mounting offset (phone yaw vs. travel), kept hot like the values above.
    @Volatile private var headingOffsetDeg: Float = PreferencesStore.DEFAULT_HEADING_OFFSET.toFloat()

    private val headingProvider: HeadingProvider by lazy { HeadingProvider(this) }

    /** Raw, uncorrected sensor azimuth [0,360) (or NaN). Used to calibrate the mounting offset. */
    val rawHeadingDegrees: Float get() = headingProvider.degrees

    /** True when the device actually has a heading sensor to calibrate against. */
    val hasHeadingSensor: Boolean get() = headingProvider.hasSensor

    /** True when the real BLE source is in use (and BLE runtime permissions are needed). */
    val usesBle: Boolean by lazy { !isEmulator() }

    /** The real BLE source when on hardware, else null (emulator uses simulated data). */
    val bleSource: CscBleDataSource? by lazy {
        if (isEmulator()) null
        else CscBleDataSource(
            context = this,
            wheelCircumferenceM = { circumferenceM },
            // Correct the raw azimuth for how the phone is mounted before anyone uses it.
            heading = { applyMountingOffset(headingProvider.degrees, headingOffsetDeg) },
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
        appScope.launch {
            prefs.wheelCircumferenceMeters.collect { circumferenceM = it }
        }
        appScope.launch {
            prefs.magneticDeclinationDeg.collect { declinationDeg = it.toFloat() }
        }
        appScope.launch {
            prefs.headingOffsetDeg.collect { headingOffsetDeg = it.toFloat() }
        }
        // Stop scanning/connections AND the magnetometer in the background (battery; Android
        // also throttles a long-running low-latency scan) and resume on return to the
        // foreground. The BLE source only resumes if recording was already started, so it
        // never pre-empts the permission request on first launch.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (usesBle) headingProvider.start()
                repository.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                repository.onBackground()
                if (usesBle) headingProvider.stop()
            }
        })
    }
}

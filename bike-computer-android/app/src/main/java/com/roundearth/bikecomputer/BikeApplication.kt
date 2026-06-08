package com.roundearth.bikecomputer

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.roundearth.bikecomputer.data.BikeRepository
import com.roundearth.bikecomputer.data.CscBleDataSource
import com.roundearth.bikecomputer.data.HeadingProvider
import com.roundearth.bikecomputer.data.PreferencesStore
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

    /** The BLE source that talks to the CSC speed sensor. */
    val bleSource: CscBleDataSource by lazy {
        CscBleDataSource(
            context = this,
            wheelCircumferenceM = { circumferenceM },
            // Correct the raw azimuth for how the phone is mounted before anyone uses it.
            heading = { applyMountingOffset(headingProvider.degrees, headingOffsetDeg) },
            declination = { declinationDeg },
            pairedSensor = prefs.pairedSensor,
        )
    }

    val repository: BikeRepository by lazy {
        val db = BikeDatabase.get(this)
        BikeRepository(bleSource, prefs, db.revolutionEventDao(), appScope)
    }

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
                headingProvider.start()
                repository.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                repository.onBackground()
                headingProvider.stop()
            }
        })
    }
}

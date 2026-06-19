package com.roundearth.bikecomputer

import android.app.Application
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
    }

    /**
     * Begins (or resumes) collection: the magnetometer and the BLE recording pipeline. Called by
     * [CollectionService] so both run for the whole ride — including while the app is backgrounded
     * or the screen is off — rather than being torn down the moment the UI stops.
     *
     * Idempotent: [BikeRepository.start] no-ops a second recording job, and [HeadingProvider.start]
     * de-dupes its sensor listener, so a sticky service restart can safely re-enter this. Keeping
     * the magnetometer running here (not gated on UI visibility) is what stamps a real heading on
     * every revolution recorded in the background, so the northward reconstruction stays intact.
     */
    fun startCollection() {
        headingProvider.start()
        repository.start()
    }

    /**
     * Ends collection: stops the BLE source and the magnetometer. The recording job is left idle
     * (not cancelled) so a later [startCollection] resumes the ride rather than re-seeding the
     * odometer from zero.
     */
    fun stopCollection() {
        repository.onBackground()
        headingProvider.stop()
    }
}

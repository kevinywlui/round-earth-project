package com.roundearth.bikecomputer

import android.app.Application
import com.roundearth.bikecomputer.data.BikeRepository
import com.roundearth.bikecomputer.data.CscBleDataSource
import com.roundearth.bikecomputer.data.HeadingLogger
import com.roundearth.bikecomputer.data.HeadingProvider
import com.roundearth.bikecomputer.data.LocationLogger
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

    private val db: BikeDatabase by lazy { BikeDatabase.get(this) }

    /** The BLE source that talks to the CSC speed sensor. */
    val bleSource: CscBleDataSource by lazy {
        CscBleDataSource(
            context = this,
            wheelCircumferenceM = { circumferenceM },
            // Correct the raw azimuth for how the phone is mounted before anyone uses it.
            heading = { applyMountingOffset(headingProvider.degrees, headingOffsetDeg) },
            declination = { declinationDeg },
            pairedSensor = prefs.pairedSensor,
            // Persist a drained per-minute backlog idempotently, stamping the circumference in effect
            // now. repository is referenced lazily here, so it's initialized by the time a batch lands.
            onBacklogBatch = { batch -> appScope.launch { repository.ingestBacklog(batch, circumferenceM) } },
        )
    }

    val repository: BikeRepository by lazy {
        BikeRepository(
            bleSource, prefs,
            db.revolutionEventDao(), db.backlogMinuteDao(), db.headingMinuteDao(), db.gpsFixDao(),
            appScope,
        )
    }

    /** Per-minute compass timeline (phone-side; runs whenever collecting, independent of BLE). */
    private val headingLogger: HeadingLogger by lazy {
        HeadingLogger(
            magneticHeading = { applyMountingOffset(headingProvider.degrees, headingOffsetDeg) },
            declinationDeg = { declinationDeg },
            compassAccuracy = { headingProvider.accuracy },
            dao = db.headingMinuteDao(),
            scope = appScope,
        )
    }

    /** Optional GPS anchoring log (separate table + separate export; best-effort on FINE location). */
    private val locationLogger: LocationLogger by lazy { LocationLogger(this, db.gpsFixDao(), appScope) }

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
        headingLogger.start()   // per-minute compass timeline (phone-side, BLE-independent)
        locationLogger.start()  // optional GPS anchoring log (no-op without FINE permission)
        repository.start()
    }

    /**
     * Ends collection: stops the BLE source and the magnetometer. The recording job is left idle
     * (not cancelled) so a later [startCollection] resumes the ride rather than re-seeding the
     * odometer from zero.
     */
    fun stopCollection() {
        repository.onBackground()
        locationLogger.stop()
        headingLogger.stop()
        headingProvider.stop()
    }
}

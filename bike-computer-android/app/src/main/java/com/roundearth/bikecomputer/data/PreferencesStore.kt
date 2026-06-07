package com.roundearth.bikecomputer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bike_prefs")

class PreferencesStore(context: Context) {

    private val store = context.dataStore

    companion object {
        private val KEY_CIRCUMFERENCE = doublePreferencesKey("wheel_circumference_m")
        private val KEY_IMPERIAL = booleanPreferencesKey("use_imperial")
        private val KEY_DECLINATION = doublePreferencesKey("magnetic_declination_deg")
        private val KEY_PAIRED_SENSORS = stringSetPreferencesKey("paired_sensors")
        const val DEFAULT_CIRCUMFERENCE = 2.096  // 700c × 25mm
        const val DEFAULT_DECLINATION = 0.0      // true == magnetic until the user sets it
    }

    val wheelCircumferenceMeters: Flow<Double> = store.data.map {
        it[KEY_CIRCUMFERENCE] ?: DEFAULT_CIRCUMFERENCE
    }

    val useImperial: Flow<Boolean> = store.data.map {
        it[KEY_IMPERIAL] ?: false
    }

    /** Local magnetic declination in degrees (positive east); added to magnetic heading. */
    val magneticDeclinationDeg: Flow<Double> = store.data.map {
        it[KEY_DECLINATION] ?: DEFAULT_DECLINATION
    }

    /** BLE addresses of sensors the user has chosen to connect to. */
    val pairedSensors: Flow<Set<String>> = store.data.map {
        it[KEY_PAIRED_SENSORS] ?: emptySet()
    }

    suspend fun setWheelCircumference(meters: Double) {
        store.edit { it[KEY_CIRCUMFERENCE] = meters }
    }

    suspend fun setUseImperial(imperial: Boolean) {
        store.edit { it[KEY_IMPERIAL] = imperial }
    }

    suspend fun setMagneticDeclination(degrees: Double) {
        store.edit { it[KEY_DECLINATION] = degrees }
    }

    suspend fun setPairedSensors(addresses: Set<String>) {
        store.edit { it[KEY_PAIRED_SENSORS] = addresses }
    }
}

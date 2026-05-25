package com.roundearth.bikecomputer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bike_prefs")

class PreferencesStore(context: Context) {

    private val store = context.dataStore

    companion object {
        private val KEY_CIRCUMFERENCE = doublePreferencesKey("wheel_circumference_m")
        private val KEY_IMPERIAL = booleanPreferencesKey("use_imperial")
        const val DEFAULT_CIRCUMFERENCE = 2.096  // 700c × 25mm
    }

    val wheelCircumferenceMeters: Flow<Double> = store.data.map {
        it[KEY_CIRCUMFERENCE] ?: DEFAULT_CIRCUMFERENCE
    }

    val useImperial: Flow<Boolean> = store.data.map {
        it[KEY_IMPERIAL] ?: false
    }

    suspend fun setWheelCircumference(meters: Double) {
        store.edit { it[KEY_CIRCUMFERENCE] = meters }
    }

    suspend fun setUseImperial(imperial: Boolean) {
        store.edit { it[KEY_IMPERIAL] = imperial }
    }
}

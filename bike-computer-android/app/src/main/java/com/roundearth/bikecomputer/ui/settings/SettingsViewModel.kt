package com.roundearth.bikecomputer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roundearth.bikecomputer.data.BikeRepository
import com.roundearth.bikecomputer.data.PreferencesStore
import com.roundearth.bikecomputer.data.db.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val wheelCircumferenceM: Double = PreferencesStore.DEFAULT_CIRCUMFERENCE,
    val useImperial: Boolean = false,
    val magneticDeclinationDeg: Double = PreferencesStore.DEFAULT_DECLINATION,
    val recordedEventCount: Int = 0,
    val sessions: List<SessionSummary> = emptyList(),
)

class SettingsViewModel(
    private val prefs: PreferencesStore,
    private val repository: BikeRepository,
) : ViewModel() {

    val uiState = combine(
        prefs.wheelCircumferenceMeters,
        prefs.useImperial,
        prefs.magneticDeclinationDeg,
        repository.recordedEventCount,
        repository.sessions,
    ) { circumference, imperial, declination, count, sessions ->
        SettingsUiState(
            wheelCircumferenceM = circumference,
            useImperial = imperial,
            magneticDeclinationDeg = declination,
            recordedEventCount = count,
            sessions = sessions,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setWheelCircumference(meters: Double) {
        viewModelScope.launch { prefs.setWheelCircumference(meters) }
    }

    fun setUseImperial(imperial: Boolean) {
        viewModelScope.launch { prefs.setUseImperial(imperial) }
    }

    fun setMagneticDeclination(degrees: Double) {
        viewModelScope.launch { prefs.setMagneticDeclination(degrees) }
    }

    /** Builds the CSV off the main thread and hands it back via [onReady] for sharing. */
    fun exportCsv(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val csv = withContext(Dispatchers.IO) { repository.exportCsv() }
            onReady(csv)
        }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    class Factory(
        private val prefs: PreferencesStore,
        private val repository: BikeRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(prefs, repository) as T
    }
}

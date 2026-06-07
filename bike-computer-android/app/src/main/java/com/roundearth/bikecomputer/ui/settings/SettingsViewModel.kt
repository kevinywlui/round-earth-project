package com.roundearth.bikecomputer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roundearth.bikecomputer.data.BikeRepository
import com.roundearth.bikecomputer.data.PreferencesStore
import com.roundearth.bikecomputer.data.db.SessionSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val wheelCircumferenceM: Double = PreferencesStore.DEFAULT_CIRCUMFERENCE,
    val useImperial: Boolean = false,
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
        repository.recordedEventCount,
        repository.sessions,
    ) { circumference, imperial, count, sessions ->
        SettingsUiState(
            wheelCircumferenceM = circumference,
            useImperial = imperial,
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

    /** Builds the CSV and hands it back via [onReady] for sharing. */
    fun exportCsv(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(repository.exportCsv()) }
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

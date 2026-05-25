package com.roundearth.bikecomputer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roundearth.bikecomputer.data.PreferencesStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val wheelCircumferenceM: Double = PreferencesStore.DEFAULT_CIRCUMFERENCE,
    val useImperial: Boolean = false,
)

class SettingsViewModel(private val prefs: PreferencesStore) : ViewModel() {

    val uiState = combine(prefs.wheelCircumferenceMeters, prefs.useImperial) { circumference, imperial ->
        SettingsUiState(wheelCircumferenceM = circumference, useImperial = imperial)
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

    class Factory(private val prefs: PreferencesStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(prefs) as T
    }
}

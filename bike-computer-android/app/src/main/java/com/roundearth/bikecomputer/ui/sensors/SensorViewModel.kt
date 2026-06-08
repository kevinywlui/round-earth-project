package com.roundearth.bikecomputer.ui.sensors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roundearth.bikecomputer.data.CscBleDataSource
import com.roundearth.bikecomputer.data.PreferencesStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SensorViewModel(
    source: CscBleDataSource,
    private val prefs: PreferencesStore,
) : ViewModel() {

    val sensors = source.discovered.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** Select this sensor, or unpair it if it was already the chosen one. */
    fun togglePair(address: String) {
        viewModelScope.launch {
            val current = prefs.pairedSensor.first()
            prefs.setPairedSensor(if (address == current) null else address)
        }
    }

    class Factory(
        private val source: CscBleDataSource,
        private val prefs: PreferencesStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SensorViewModel(source, prefs) as T
    }
}

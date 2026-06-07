package com.roundearth.bikecomputer.ui.sensors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roundearth.bikecomputer.data.CscBleDataSource
import com.roundearth.bikecomputer.data.DiscoveredSensor
import com.roundearth.bikecomputer.data.PreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SensorViewModel(
    source: CscBleDataSource?,
    private val prefs: PreferencesStore,
) : ViewModel() {

    // On an emulator there is no BLE source, so the discovery list stays empty.
    val sensors = (source?.discovered ?: MutableStateFlow(emptyList<DiscoveredSensor>())).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** Pair or unpair a sensor by address; the data source reacts to the change. */
    fun togglePair(address: String) {
        viewModelScope.launch {
            val current = prefs.pairedSensors.first()
            prefs.setPairedSensors(
                if (address in current) current - address else current + address,
            )
        }
    }

    class Factory(
        private val source: CscBleDataSource?,
        private val prefs: PreferencesStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SensorViewModel(source, prefs) as T
    }
}

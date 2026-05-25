package com.roundearth.bikecomputer.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roundearth.bikecomputer.data.BikeRepository
import com.roundearth.bikecomputer.ui.BikeUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class BikeViewModel(repository: BikeRepository) : ViewModel() {

    val uiState = combine(repository.bikeData, repository.connectionState) { data, conn ->
        BikeUiState(
            speed = data.speed,
            cadenceRpm = data.cadenceRpm,
            bearingDegrees = data.bearingDegrees,
            odometer = data.odometer,
            connectionState = conn,
            useImperial = data.useImperial,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BikeUiState(),
    )

    class Factory(private val repository: BikeRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BikeViewModel(repository) as T
    }
}

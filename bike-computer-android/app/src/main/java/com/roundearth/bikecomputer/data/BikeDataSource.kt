package com.roundearth.bikecomputer.data

import kotlinx.coroutines.flow.Flow

interface BikeDataSource {
    val data: Flow<RawBikeData>
    val connectionState: Flow<ConnectionState>
}

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTED, SIMULATED }

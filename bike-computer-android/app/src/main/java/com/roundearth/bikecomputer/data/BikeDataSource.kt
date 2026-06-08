package com.roundearth.bikecomputer.data

import kotlinx.coroutines.flow.Flow

interface BikeDataSource {
    /** Live, derived values for the dashboard. */
    val data: Flow<RawBikeData>
    /** Raw per-revolution events to be persisted as time-series data. */
    val revolutionReadings: Flow<WheelRevolutionReading>
    val connectionState: Flow<ConnectionState>

    /** Begin scanning/connecting (or producing data). Safe to call once. */
    fun start()
    /** Release resources. */
    fun stop()

    /**
     * Seed the live odometer (meters) so a ride resumed after a process restart
     * continues from its prior distance instead of zero. No-op by default.
     */
    fun seedOdometer(meters: Double) {}
}

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTED }

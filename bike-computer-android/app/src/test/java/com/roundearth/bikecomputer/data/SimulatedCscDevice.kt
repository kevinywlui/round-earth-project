package com.roundearth.bikecomputer.data

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * A scriptable, JVM-only simulated CSC sensor for end-to-end [CscBleDataSource] tests.
 *
 * Wraps the production test seams ([CscBleDataSource.onScanResultForTest],
 * [CscBleDataSource.connectionCallbackForTest], [CscBleDataSource.pairForTest]) and a Mockito
 * mock GATT graph into a fluent scan → connect → discover → subscribe → notify → disconnect
 * lifecycle, with first-class FAILURE INJECTION via [GattGraph]. No real radio, no extra thread:
 * the captured [BluetoothGattCallback] (the private SensorConnection) is driven directly, exactly
 * as the existing [CscConnectionTest] does — this fixture just makes a whole lifecycle (and its
 * failure variants) reusable instead of hand-wired per test.
 *
 * Construct one per `@Test` against a fresh [CscBleDataSource]; call [CscBleDataSource.stop] in
 * teardown (job-leak hygiene). The device is pre-paired and `connectGatt` is pre-stubbed to return
 * [gatt]; pass `connectGattReturnsNull = true` to model a null `connectGatt`.
 */
class SimulatedCscDevice(
    private val source: CscBleDataSource,
    val address: String = "AA:BB:CC:DD:EE:01",
    connectGattReturnsNull: Boolean = false,
) {
    val device: BluetoothDevice = mock()
    val gatt: BluetoothGatt = mock()

    /** The callback owning the slot after the most recent [scanSighting], or null if none/torn down. */
    private var cb: BluetoothGattCallback? = null
    /** The graph installed by the most recent [discovered] — its CCCD mock is what [confirmCccd] writes. */
    private var lastGraph: GattGraph = GattGraph.healthy()

    init {
        whenever(device.address).thenReturn(address)
        whenever(device.connectGatt(anyOrNull(), eq(false), any()))
            .thenReturn(if (connectGattReturnsNull) null else gatt)
        source.pairForTest(address)
    }

    // --- Lifecycle script (each step returns `this` for chaining) ---

    /** Advertise this sensor; the source connects (it is paired) and we capture its GATT callback. */
    fun scanSighting(name: String = "Bike Speed", rssi: Int = -50): SimulatedCscDevice = apply {
        source.onScanResultForTest(device, name, rssi)
        cb = source.connectionCallbackForTest()
    }

    /** The radio link came up; the source will discover services. */
    fun connected(): SimulatedCscDevice = apply {
        callback().onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    }

    /**
     * Service discovery completed. [graph] decides which parts of the CSC service exist — flip a
     * toggle off to reach a production null-branch (missing characteristic, absent CCCD, …). [status]
     * defaults to success; pass a non-success code to model a failed discovery.
     */
    fun discovered(
        graph: GattGraph = GattGraph.healthy(),
        status: Int = BluetoothGatt.GATT_SUCCESS,
    ): SimulatedCscDevice = apply {
        lastGraph = graph
        graph.installOn(gatt)
        callback().onServicesDiscovered(gatt, status)
    }

    /** The CSC CCCD write completed (the step that flips the source to CONNECTED on success). */
    fun confirmCccd(status: Int = BluetoothGatt.GATT_SUCCESS): SimulatedCscDevice = apply {
        callback().onDescriptorWrite(gatt, lastGraph.cccdMock, status)
    }

    /** A clean connect → discover(healthy) → subscribe, leaving the source CONNECTED. */
    fun subscribeHealthy(): SimulatedCscDevice = connected().discovered(GattGraph.healthy()).confirmCccd()

    /** Deliver one wheel-revolution measurement notification (the API-34 ByteArray overload). */
    fun notify(revs: Long, time: Int): SimulatedCscDevice = apply {
        callback().onCharacteristicChanged(gatt, measurementChar(), wheelPacket(revs, time))
    }

    /** The peer (or stack) dropped the link — drives onConnectionStateChange(DISCONNECTED). */
    fun unsolicitedDisconnect(status: Int = GATT_CONN_TIMEOUT): SimulatedCscDevice = apply {
        callback().onConnectionStateChange(gatt, status, BluetoothProfile.STATE_DISCONNECTED)
    }

    // --- Observers ---

    /** The callback captured at the last sighting (asserting tests drive it directly). */
    fun callback(): BluetoothGattCallback = cb ?: error("no connection was opened; call scanSighting() first")

    /** The source's CURRENT slot occupant (null once torn down) — the wedge/eviction signal. */
    fun slotCallback(): BluetoothGattCallback? = source.connectionCallbackForTest()

    private fun measurementChar(): BluetoothGattCharacteristic {
        val c: BluetoothGattCharacteristic = mock()
        whenever(c.uuid).thenReturn(CSC_MEASUREMENT_UUID)
        return c
    }

    /**
     * The CSC service mock graph. Each `false` toggle OMITS a stub so the corresponding
     * `getService`/`getCharacteristic`/`getDescriptor` returns Mockito's default null — which is
     * exactly how production reaches its null-guards. The default is a fully-healthy CSC service.
     */
    data class GattGraph(
        val cscService: Boolean = true,
        val measurementChar: Boolean = true,
        val cccd: Boolean = true,
    ) {
        /** The CCCD descriptor production is handed; [confirmCccd] later writes on this same instance. */
        val cccdMock: BluetoothGattDescriptor = mock()

        fun installOn(gatt: BluetoothGatt) {
            if (!cscService) return
            val service: BluetoothGattService = mock()
            whenever(gatt.getService(CSC_SERVICE_UUID)).thenReturn(service)
            if (!measurementChar) return
            val measurement: BluetoothGattCharacteristic = mock()
            whenever(service.getCharacteristic(CSC_MEASUREMENT_UUID)).thenReturn(measurement)
            if (!cccd) return
            whenever(measurement.getDescriptor(CCCD_UUID)).thenReturn(cccdMock)
        }

        companion object {
            /** Full, working CSC service (mirrors CscConnectionTest.stubCscService). */
            fun healthy() = GattGraph()
            /** Service present but the CSC Measurement characteristic is absent (bug #1). */
            fun missingMeasurementChar() = GattGraph(measurementChar = false)
            /** Measurement present but its 0x2902 CCCD descriptor is absent (bug #4). */
            fun missingCccd() = GattGraph(cccd = false)
        }
    }

    companion object {
        // Mirrors BluetoothGatt.GATT_CONN_TIMEOUT (8) — the status a half-open link finally dies with.
        const val GATT_CONN_TIMEOUT = 8

        val CSC_SERVICE_UUID: UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
        val CSC_MEASUREMENT_UUID: UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** The exact 7-byte wheel-only CSC packet this firmware emits (flags 0x01). */
        fun wheelPacket(revs: Long, time: Int): ByteArray = byteArrayOf(
            0x01,
            (revs and 0xFF).toByte(),
            ((revs shr 8) and 0xFF).toByte(),
            ((revs shr 16) and 0xFF).toByte(),
            ((revs shr 24) and 0xFF).toByte(),
            (time and 0xFF).toByte(),
            ((time shr 8) and 0xFF).toByte(),
        )
    }
}

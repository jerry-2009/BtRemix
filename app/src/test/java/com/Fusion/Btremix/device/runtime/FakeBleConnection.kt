package com.Fusion.Btremix.device.runtime

import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleConnection
import com.Fusion.Btremix.core.bluetooth.api.BleService
import com.Fusion.Btremix.core.bluetooth.api.ConnectionState
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One captured GATT write. */
data class FakeWrite(
    val characteristic: BleCharacteristic,
    val data: ByteArray,
    val withResponse: Boolean,
)

/**
 * Transport-level fake shared by Device Runtime and Definition session tests.
 *
 * Notification flows keep one replayed value so a test can emit before a script subscribes and the
 * `ble.subscribe` step still observes it, matching the "wait for the latest notification" contract.
 */
class FakeBleConnection(
    private val discoveredServices: List<BleService> = listOf(DEFAULT_SERVICE),
    private val readValue: ByteArray = byteArrayOf(),
) : BleConnection {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    private val notificationFlows = mutableMapOf<String, MutableSharedFlow<ByteArray>>()

    override val state = mutableState.asStateFlow()

    var discoverCount = 0
        private set

    var disconnected = false
        private set

    val writes = mutableListOf<FakeWrite>()
    val reads = mutableListOf<BleCharacteristic>()

    /** Optional hook so a test can answer a write (for example with a protocol response). */
    var onWrite: suspend (FakeWrite) -> Unit = {}

    override suspend fun discoverServices(): List<BleService> {
        discoverCount++
        return discoveredServices
    }

    override suspend fun read(characteristic: BleCharacteristic): ByteArray {
        reads += characteristic
        return readValue.clone()
    }

    override suspend fun write(characteristic: BleCharacteristic, data: ByteArray, withResponse: Boolean) {
        val write = FakeWrite(characteristic, data.clone(), withResponse)
        writes += write
        onWrite(write)
    }

    override fun notifications(characteristic: BleCharacteristic): Flow<ByteArray> = flowFor(characteristic)

    override suspend fun disconnect() {
        disconnected = true
        mutableState.value = ConnectionState.Disconnected
    }

    /** Emits one notification for [characteristic]; a later subscriber still observes it. */
    suspend fun emitNotification(characteristic: BleCharacteristic, data: ByteArray) {
        flowFor(characteristic).emit(data.clone())
    }

    private fun flowFor(characteristic: BleCharacteristic): MutableSharedFlow<ByteArray> =
        synchronized(notificationFlows) {
            notificationFlows.getOrPut("${characteristic.serviceUuid}/${characteristic.uuid}") {
                MutableSharedFlow(replay = 1, extraBufferCapacity = 16)
            }
        }

    companion object {
        val DEFAULT_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val DEFAULT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEFAULT_SERVICE: BleService = BleService(DEFAULT_SERVICE_UUID)
    }
}

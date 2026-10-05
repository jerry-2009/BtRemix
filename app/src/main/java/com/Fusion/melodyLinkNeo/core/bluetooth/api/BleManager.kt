package com.fusion.melodyLinkNeo.core.bluetooth.api

import kotlinx.coroutines.flow.Flow

interface BleManager {
    fun scan(): Flow<BleScanResult>
    suspend fun stopScan()
    suspend fun connect(device: BleDevice): BleConnection
}

interface BleConnection {
    val state: kotlinx.coroutines.flow.StateFlow<ConnectionState>

    suspend fun discoverServices(): List<BleService>
    suspend fun read(characteristic: BleCharacteristic): ByteArray
    suspend fun write(characteristic: BleCharacteristic, data: ByteArray, withResponse: Boolean = true)
    fun notifications(characteristic: BleCharacteristic): Flow<ByteArray>
    suspend fun disconnect()
}

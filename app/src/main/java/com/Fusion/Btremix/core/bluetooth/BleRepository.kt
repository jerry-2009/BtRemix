package com.Fusion.Btremix.core.bluetooth

import com.Fusion.Btremix.core.bluetooth.api.BleConnection
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleManager
import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.core.logging.LogCategory
import com.Fusion.Btremix.core.logging.LogEntry
import com.Fusion.Btremix.core.logging.Logger
import java.time.Instant
import kotlinx.coroutines.flow.Flow

class BleRepository(private val manager: BleManager, private val logger: Logger) {
    fun scan(): Flow<BleScanResult> = manager.scan()

    suspend fun stopScan() = manager.stopScan()

    suspend fun connect(device: BleDevice): BleConnection {
        logger.log(LogEntry(Instant.now(), LogCategory.CONNECTION, "Connecting", device.id))
        return manager.connect(device).also {
            logger.log(LogEntry(Instant.now(), LogCategory.CONNECTION, "Connected", device.id))
        }
    }

    fun log(category: LogCategory, message: String, deviceId: String? = null, uuid: String? = null, data: ByteArray? = null) {
        logger.log(LogEntry(Instant.now(), category, message, deviceId, uuid, data?.clone()))
    }

    val logs = logger.entries

    fun clearLogs() = logger.clear()
}

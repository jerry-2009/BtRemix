package com.fusion.melodyLinkNeo.core.bluetooth.api

import java.util.UUID

/** A stable description of a BLE peripheral. */
data class BleDevice(
    val id: String,
    val name: String? = null,
    val address: String = id,
)

/** One observation produced by a BLE scan. */
data class BleScanResult(
    val device: BleDevice,
    val rssi: Int,
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val serviceUuids: List<UUID> = emptyList(),
)

data class BleService(
    val uuid: UUID,
    val isPrimary: Boolean = true,
    val characteristics: List<BleCharacteristic> = emptyList(),
)

data class BleCharacteristic(
    val serviceUuid: UUID,
    val uuid: UUID,
    val properties: Set<BleCharacteristicProperty> = emptySet(),
    val descriptors: List<BleDescriptor> = emptyList(),
)

data class BleDescriptor(
    val characteristicUuid: UUID,
    val uuid: UUID,
)

enum class BleCharacteristicProperty {
    READ,
    WRITE,
    WRITE_WITHOUT_RESPONSE,
    NOTIFY,
    INDICATE,
    BROADCAST,
    SIGNED_WRITE;

    companion object {
        fun fromAndroidProperties(properties: Int): Set<BleCharacteristicProperty> = buildSet {
            if (properties and 0x02 != 0) add(READ)
            if (properties and 0x08 != 0) add(WRITE)
            if (properties and 0x04 != 0) add(WRITE_WITHOUT_RESPONSE)
            if (properties and 0x10 != 0) add(NOTIFY)
            if (properties and 0x20 != 0) add(INDICATE)
            if (properties and 0x01 != 0) add(BROADCAST)
            if (properties and 0x40 != 0) add(SIGNED_WRITE)
        }
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data object DiscoveringServices : ConnectionState
    data object Ready : ConnectionState
    data object Disconnecting : ConnectionState
    data class Error(val error: BleError) : ConnectionState
}

sealed interface BleError {
    val message: String

    data class BluetoothUnavailable(override val message: String = "Bluetooth is unavailable") : BleError
    data class PermissionDenied(override val message: String = "Bluetooth permission was denied") : BleError
    data class Timeout(override val message: String) : BleError
    data class DeviceUnavailable(override val message: String) : BleError
    data class Gatt(val status: Int, override val message: String = "GATT operation failed (status=$status)") : BleError
    data class InvalidState(override val message: String) : BleError
    data class Unknown(override val message: String, val cause: Throwable? = null) : BleError
}

class BleException(val error: BleError) : IllegalStateException(error.message)

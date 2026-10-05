package com.fusion.melodyLinkNeo.core.classic.api

import com.fusion.melodyLinkNeo.core.transport.api.ByteStream
import com.fusion.melodyLinkNeo.core.transport.api.TransportState
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

/** One paired (bonded) classic Bluetooth device. */
data class ClassicDevice(
    val address: String,
    val name: String? = null,
    val bonded: Boolean = true,
)

/** A connected RFCOMM socket to a classic Bluetooth device. */
interface RfcommConnection : ByteStream {
    val address: String
    val name: String?
    val state: StateFlow<TransportState>
}

/**
 * Classic Bluetooth (RFCOMM/SPP) backend.
 *
 * Only bonded devices are exposed: SPP requires a prior system pairing, and classic discovery is
 * unreliable on Android 12+ with `neverForLocation`. This is a transport backend like the BLE one;
 * it knows nothing about any specific device or protocol.
 */
interface RfcommManager {
    /** Bonded classic devices, or an empty list when Bluetooth is off or unavailable. */
    suspend fun bondedDevices(): List<ClassicDevice>

    suspend fun connect(address: String, serviceUuid: UUID): RfcommConnection

    suspend fun close()
}

class RfcommException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

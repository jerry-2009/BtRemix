package com.Fusion.Btremix.core.transport.api

import kotlinx.coroutines.flow.Flow

/**
 * Transport backend SPI: a bidirectional byte pipe.
 *
 * This is deliberately vendor- and technology-neutral. BLE/GATT has its own richer API
 * (`core.bluetooth`); every other backend (Bluetooth Classic RFCOMM today, USB/Wi-Fi later) only
 * has to provide a byte stream, and the protocol layer does its own framing on top of it.
 */
interface ByteStream {
    /** Chunks read from the peer. Each chunk is arbitrary: framing is the caller's job. */
    fun incoming(): Flow<ByteArray>

    suspend fun write(bytes: ByteArray)

    suspend fun close()
}

/** Connection state of a generic transport backend, independent of BLE. */
sealed interface TransportState {
    data object Disconnected : TransportState
    data object Connecting : TransportState
    data object Connected : TransportState
    data class Error(val message: String, val cause: Throwable? = null) : TransportState
}

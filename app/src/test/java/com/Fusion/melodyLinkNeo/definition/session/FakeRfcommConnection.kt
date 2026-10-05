package com.fusion.melodyLinkNeo.definition.session

import com.fusion.melodyLinkNeo.core.classic.api.RfcommConnection
import com.fusion.melodyLinkNeo.core.transport.api.TransportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory classic Bluetooth connection for SPP tests: recorded writes, injectable input and an
 * optional write hook that answers a request with the response frame.
 */
class FakeRfcommConnection(
    override val address: String = "AA:BB:CC:DD:EE:FF",
    override val name: String? = "WF-1000XM3",
) : RfcommConnection {
    private val input = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val mutableState = MutableStateFlow<TransportState>(TransportState.Connected)

    val writes = mutableListOf<ByteArray>()
    var closed = false
        private set

    /** Invoked after a write is recorded, so a test can inject the matching response frame. */
    var onWrite: suspend (ByteArray) -> Unit = {}

    override val state: StateFlow<TransportState> = mutableState.asStateFlow()

    override fun incoming(): Flow<ByteArray> = input.asSharedFlow()

    override suspend fun write(bytes: ByteArray) {
        writes += bytes.clone()
        onWrite(bytes.clone())
    }

    override suspend fun close() {
        closed = true
        mutableState.value = TransportState.Disconnected
    }

    suspend fun emit(chunk: ByteArray) {
        input.emit(chunk.clone())
    }
}

package com.fusion.melodyLinkNeo.protocol

import com.fusion.melodyLinkNeo.core.transport.api.ByteStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

/**
 * In-memory byte stream for protocol tests: injectable input, recorded output and a way to wait for
 * the framing layer to subscribe before bytes are pushed in.
 */
class FakeByteStream : ByteStream {
    private val input = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    val writes = mutableListOf<ByteArray>()
    var closed = false
        private set

    val subscriptionCount: StateFlow<Int> get() = input.subscriptionCount

    override fun incoming(): Flow<ByteArray> = input.asSharedFlow()

    override suspend fun write(bytes: ByteArray) {
        writes += bytes.clone()
    }

    override suspend fun close() {
        closed = true
    }

    suspend fun emit(chunk: ByteArray) {
        input.emit(chunk.clone())
    }

    suspend fun awaitSubscriber() {
        subscriptionCount.first { it > 0 }
    }
}

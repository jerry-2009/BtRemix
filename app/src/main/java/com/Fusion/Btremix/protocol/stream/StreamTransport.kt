package com.Fusion.Btremix.protocol.stream

import com.Fusion.Btremix.core.transport.api.ByteStream
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.ProtocolTransport
import com.Fusion.Btremix.protocol.api.StreamCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ProtocolTransport] over any byte-stream backend.
 *
 * Responsibilities kept here (and deliberately *not* in a Definition or a vendor codec):
 *
 * - framing: raw chunks become complete wire frames through [StreamCodec.accumulator];
 * - automatic replies: whatever [StreamCodec.automaticReplies] returns is written before the frame
 *   is handed to the transaction runtime;
 * - write serialisation: the init sequence, action transactions and automatic replies all write to
 *   one pipe, and writes must not interleave.
 *
 * One reader coroutine owns the stream, so automatic replies are sent exactly once even when a
 * transaction and a continuous notification binding subscribe at the same time. Frames the codec
 * cannot parse are dropped.
 */
class StreamTransport(
    private val stream: ByteStream,
    private val codec: StreamCodec,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ProtocolTransport {
    private val writeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val framesImpl = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    init {
        scope.launch { runCatching { readStream() } }
    }

    /** Complete wire frames as they arrived, including automatically replied ones. */
    fun frames(): Flow<ByteArray> = framesImpl.asSharedFlow()

    /** Complete wire frames decoded into [Packet]s. */
    fun packets(): Flow<Packet> = frames().map { frame -> codec.decode(frame) }

    override fun notifications(): Flow<ByteArray> = frames()

    override suspend fun write(data: ByteArray) {
        writeMutex.withLock { stream.write(data) }
    }

    suspend fun close() {
        scope.cancel()
        stream.close()
    }

    private suspend fun readStream() {
        val accumulator = codec.accumulator()
        stream.incoming().collect { chunk ->
            accumulator.feed(chunk).forEach { frame ->
                val packet = runCatching { codec.decode(frame) }.getOrNull() ?: return@forEach
                codec.automaticReplies(packet).forEach { reply -> write(codec.encode(reply)) }
                framesImpl.emit(frame)
            }
        }
    }
}

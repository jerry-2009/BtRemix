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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** One complete candidate frame that was split successfully but rejected by the codec. */
data class BadFrameDiagnostic(
    val frame: ByteArray,
    val error: Throwable,
) {
    fun copyFrame(): ByteArray = frame.clone()
}

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
    private val onBadFrame: ((BadFrameDiagnostic) -> Unit)? = null,
) : ProtocolTransport {
    private val writeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val framesImpl = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val badFramesImpl = MutableSharedFlow<BadFrameDiagnostic>(extraBufferCapacity = 64)
    private val accumulator = codec.accumulator()
    private val badFrames = AtomicLong()

    init {
        scope.launch { runCatching { readStream() } }
    }

    /** Complete wire frames as they arrived, including automatically replied ones. */
    fun frames(): Flow<ByteArray> = framesImpl.asSharedFlow()

    /** Codec-level rejections. A bad frame never terminates the reader coroutine. */
    val badFrameEvents: SharedFlow<BadFrameDiagnostic> = badFramesImpl.asSharedFlow()

    val badFrameCount: Long get() = badFrames.get()

    /** Complete wire frames decoded into [Packet]s. */
    fun packets(): Flow<Packet> = frames().map { frame -> codec.decode(frame) }

    override fun notifications(): Flow<ByteArray> = frames()

    override suspend fun write(data: ByteArray) {
        writeMutex.withLock { stream.write(data) }
    }

    suspend fun close() {
        scope.cancel()
        accumulator.reset()
        stream.close()
    }

    private suspend fun readStream() {
        stream.incoming().collect { chunk ->
            val candidates = ArrayDeque<ByteArray>()
            accumulator.feed(chunk).forEach(candidates::addLast)
            while (candidates.isNotEmpty()) {
                val frame = candidates.removeFirst()
                val packet = try {
                    codec.decode(frame)
                } catch (error: Throwable) {
                    badFrames.incrementAndGet()
                    val diagnostic = BadFrameDiagnostic(frame.clone(), error)
                    runCatching { onBadFrame?.invoke(diagnostic) }
                    badFramesImpl.emit(diagnostic)
                    accumulator.reject(frame).forEach(candidates::addLast)
                    continue
                }
                codec.automaticReplies(packet).forEach { reply -> write(codec.encode(reply)) }
                framesImpl.emit(frame.clone())
            }
        }
    }
}

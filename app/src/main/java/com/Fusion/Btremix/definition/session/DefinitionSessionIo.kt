package com.Fusion.Btremix.definition.session

import com.Fusion.Btremix.definition.api.DefinitionProtocolFactory
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.PacketMatcherSpec
import com.Fusion.Btremix.protocol.api.ProtocolRuntime
import com.Fusion.Btremix.protocol.api.SessionIo
import com.Fusion.Btremix.protocol.api.SessionRequest
import com.Fusion.Btremix.protocol.api.SessionSubscription
import com.Fusion.Btremix.protocol.api.TransactionResult
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Definition-level implementation of the controlled Session I/O.
 *
 * Both adapters converge here after resolving their native endpoint (a characteristic or the raw
 * stream). Framing and transaction matching stay in the protocol runtime; script callers only see
 * logical transaction names and decoded packets.
 */
internal class DefinitionSessionIo(
    private val definition: LoadedDeviceDefinition,
    private val factory: DefinitionProtocolFactory,
    private val runtime: ProtocolRuntime,
    incoming: Flow<ByteArray>,
    lifecycle: Flow<DeviceLifecycleState>,
    private val writeRaw: suspend (ByteArray) -> Unit,
    dispatcher: CoroutineDispatcher,
) : SessionIo {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineName("DefinitionSessionIo:${definition.id}"),
    )
    private val writeMutex = Mutex()
    private val jobs = Collections.synchronizedList(mutableListOf<Job>())
    private val packets: Flow<Packet> = incoming.mapNotNull { bytes ->
        runCatching { factory.packetCodec.decode(bytes) }.getOrNull()
    }
    private val closed = AtomicBoolean(false)

    override val endpoints: Set<String> = definition.protocol.transactions.keys.toSet()

    init {
        scope.launch {
            lifecycle.collect { state ->
                when (state) {
                    DeviceLifecycleState.Disconnecting,
                    DeviceLifecycleState.Disconnected,
                    is DeviceLifecycleState.Error,
                    -> close()
                    else -> Unit
                }
            }
        }
    }

    override suspend fun send(bytes: ByteArray) {
        checkOpen()
        validateSize(bytes)
        writeMutex.withLock { writeRaw(bytes.clone()) }
    }

    override suspend fun send(request: SessionRequest) {
        checkOpen()
        require(request.endpoint in endpoints) {
            "Unknown session endpoint '${request.endpoint}'; declared ${endpoints.sorted()}"
        }
        val declared = request.packet?.let { packet ->
            factory.transactionForPacket(request.endpoint, packet)
        } ?: factory.transaction(request.endpoint)
        writeMutex.withLock {
            runtime.send(request.packet ?: declared.request)
        }
    }

    override suspend fun request(request: SessionRequest): TransactionResult {
        checkOpen()
        val transaction = definition.protocol.transactions[request.endpoint]
            ?: throw IllegalArgumentException(
                "Unknown session endpoint '${request.endpoint}'; declared ${endpoints.sorted()}",
            )
        val declared = request.packet?.let { packet ->
            factory.transactionForPacket(request.endpoint, packet)
        } ?: factory.transaction(request.endpoint)
        val resolved = declared.copy(
            timeout = request.timeout ?: transaction.timeout,
            retries = request.retries ?: transaction.retries,
            matcherSpec = request.matcher ?: declared.matcherSpec,
        )
        return writeMutex.withLock { runtime.execute(resolved) }
    }

    override suspend fun waitFor(matcher: PacketMatcherSpec, timeout: Duration): Packet {
        checkOpen()
        require(!matcher.matchRequestSequence) {
            "waitFor cannot reference a request sequence"
        }
        return withTimeout(timeout) {
            packets.first { packet -> matcher.matches(packet, requestSequence = null) }
        }
    }

    override fun onFrame(
        matcher: PacketMatcherSpec,
        handler: suspend (Packet) -> Unit,
    ): SessionSubscription {
        checkOpen()
        require(!matcher.matchRequestSequence) {
            "onFrame cannot reference a request sequence"
        }
        val cancelled = AtomicBoolean(false)
        val job = scope.launch {
            packets.collect { packet ->
                if (cancelled.get()) return@collect
                if (!matcher.matches(packet, requestSequence = null)) return@collect
                try {
                    handler(packet)
                } catch (cancelledError: CancellationException) {
                    throw cancelledError
                } catch (_: Throwable) {
                    // One bad event program must not terminate the session's frame fan-out.
                }
            }
        }
        jobs += job
        return SessionSubscription {
            if (cancelled.compareAndSet(false, true)) {
                job.cancel()
                jobs.remove(job)
            }
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(jobs) {
            jobs.forEach(Job::cancel)
            jobs.clear()
        }
        scope.cancel()
    }

    private fun checkOpen() {
        check(!closed.get()) { "Session I/O is closed" }
    }

    private fun validateSize(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Session send cannot be empty" }
        require(bytes.size <= MAX_SEND_BYTES) {
            "Session send is ${bytes.size} bytes; hard limit is $MAX_SEND_BYTES"
        }
    }

    private companion object {
        const val MAX_SEND_BYTES = 8192
    }
}

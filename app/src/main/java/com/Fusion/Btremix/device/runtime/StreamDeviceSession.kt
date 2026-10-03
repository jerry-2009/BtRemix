package com.Fusion.Btremix.device.runtime

import com.Fusion.Btremix.core.classic.api.RfcommConnection
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.transport.api.TransportState
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.StreamCodec
import com.Fusion.Btremix.protocol.stream.StreamTransport
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Transport-neutral session for a classic Bluetooth byte stream (RFCOMM/SPP).
 *
 * There is no service discovery, no characteristic and therefore no `services`: a stream session is
 * ready as soon as the socket is connected, and protocol logic uses [packets] instead of GATT
 * notifications. Everything else - state storage, event fan-out, action dispatch - mirrors
 * [DefaultDeviceRuntime]'s GATT session so Definition bindings and the UI can treat both alike.
 */
class StreamDeviceSession(
    override val device: BleDevice,
    private val connection: RfcommConnection,
    private val codec: StreamCodec,
    private val clock: Clock = Clock.systemUTC(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ProtocolSession {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val eventBus = EventBus(clock)
    private val stateImpl = InMemoryStateStore(clock)
    private val lifecycleImpl = MutableStateFlow<DeviceLifecycleState>(DeviceLifecycleState.Created)
    private val handlers = ConcurrentHashMap<String, DeviceActionHandler>()
    private val actionMutex = Mutex()
    private var connectionMonitor: Job? = null
    @Volatile private var closed = false

    val transport: StreamTransport = StreamTransport(connection, codec, dispatcher)

    override val lifecycle: StateFlow<DeviceLifecycleState> = lifecycleImpl.asStateFlow()
    override val state: StateStore = stateImpl
    override val stateStore: StateStore = stateImpl
    override val events: SharedFlow<DeviceEvent> = eventBus.events

    init {
        scope.launch {
            stateImpl.changes.collect { change ->
                eventBus.emit(
                    DeviceEvent.StateChanged(
                        deviceId = device.id,
                        timestamp = change.entry.timestamp,
                        key = change.key,
                        oldValue = change.previous?.value,
                        newValue = change.entry.value,
                        entry = change.entry,
                    ),
                )
            }
        }
    }

    /** Decoded protocol packets received on the stream, including frames nobody is waiting for. */
    fun packets(): Flow<Packet> = transport.packets()

    /** Brings the session to [DeviceLifecycleState.Ready]; called once by the session factory. */
    suspend fun initialize() {
        transition(DeviceLifecycleState.Connected)
        transition(DeviceLifecycleState.Initializing)
        connectionMonitor = scope.launch {
            connection.state
                .filter { it is TransportState.Disconnected || it is TransportState.Error }
                .collectLatest { state ->
                    if (!closed) {
                        if (state is TransportState.Error) {
                            transition(DeviceLifecycleState.Error(RuntimeError.ConnectionFailed(state.message)))
                        } else {
                            transition(DeviceLifecycleState.Disconnected)
                        }
                        closeAfterFailure()
                    }
                }
        }
        try {
            transition(DeviceLifecycleState.Ready)
            eventBus.emit(DeviceEvent.DeviceReady(device.id, now()))
        } catch (cancelled: CancellationException) {
            closeAfterFailure()
            throw cancelled
        } catch (error: Throwable) {
            transition(DeviceLifecycleState.Error(RuntimeError.InitializationFailed(error.message ?: "Stream session failed")))
            closeAfterFailure()
            throw error
        }
    }

    override fun registerAction(id: String, handler: DeviceActionHandler) {
        require(id.isNotBlank()) { "Action id cannot be blank" }
        handlers[id] = handler
    }

    override suspend fun execute(action: DeviceAction): ActionResult = actionMutex.withLock {
        if (closed || lifecycle.value != DeviceLifecycleState.Ready) {
            val result = ActionResult.Failure(RuntimeError.InvalidState("Device is not ready"))
            eventBus.emit(DeviceEvent.ActionFailed(device.id, now(), action, result.error))
            return@withLock result
        }
        eventBus.emit(DeviceEvent.ActionStarted(device.id, now(), action))
        val handler = handlers[action.id]
        if (handler == null) {
            val result = ActionResult.Failure(RuntimeError.ActionNotFound(action.id))
            eventBus.emit(DeviceEvent.ActionFailed(device.id, now(), action, result.error))
            return@withLock result
        }
        try {
            val result = handler.handle(action)
            when (result) {
                is ActionResult.Success -> eventBus.emit(DeviceEvent.ActionCompleted(device.id, now(), action, result))
                is ActionResult.Failure -> eventBus.emit(DeviceEvent.ActionFailed(device.id, now(), action, result.error))
            }
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val result = ActionResult.Failure(RuntimeError.ActionFailed(action.id, error.message ?: "Action failed", error))
            eventBus.emit(DeviceEvent.ActionFailed(device.id, now(), action, result.error))
            result
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        transition(DeviceLifecycleState.Disconnecting)
        connectionMonitor?.cancel()
        try {
            transport.close()
        } finally {
            transition(DeviceLifecycleState.Disconnected)
            scope.cancel()
        }
    }

    private suspend fun closeAfterFailure() {
        if (closed) return
        closed = true
        connectionMonitor?.cancel()
        runCatching { transport.close() }
        transition(DeviceLifecycleState.Disconnected)
        scope.cancel()
    }

    private suspend fun transition(next: DeviceLifecycleState) {
        val previous = lifecycleImpl.value
        if (previous == next) return
        lifecycleImpl.value = next
        eventBus.emit(DeviceEvent.LifecycleChanged(device.id, now(), previous, next))
        when (next) {
            DeviceLifecycleState.Connected -> eventBus.emit(DeviceEvent.DeviceConnected(device.id, now()))
            DeviceLifecycleState.Disconnected -> eventBus.emit(DeviceEvent.DeviceDisconnected(device.id, now()))
            is DeviceLifecycleState.Error -> eventBus.emit(DeviceEvent.Error(device.id, now(), next.error))
            else -> Unit
        }
    }

    private fun now() = clock.instant()
}

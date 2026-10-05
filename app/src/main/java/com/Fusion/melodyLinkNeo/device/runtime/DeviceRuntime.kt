package com.fusion.melodyLinkNeo.device.runtime

import com.fusion.melodyLinkNeo.core.bluetooth.api.BleCharacteristic
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleConnection
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleService
import com.fusion.melodyLinkNeo.core.bluetooth.api.ConnectionState
import java.time.Clock
import java.time.Instant
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Creates runtime sessions for already connected BLE devices. */
interface DeviceRuntime {
    suspend fun open(device: BleDevice, connection: BleConnection): DeviceSession
}

/**
 * Transport-neutral view of a live device session.
 *
 * GATT sessions ([DeviceSession]) and classic-Bluetooth byte-stream sessions ([StreamDeviceSession])
 * both expose lifecycle, state, events and executable actions; only the transport below them
 * differs. Definition binding, the state store and the UI all build on this interface.
 */
interface ProtocolSession {
    val device: BleDevice
    val lifecycle: StateFlow<DeviceLifecycleState>
    val lifecycleState: StateFlow<DeviceLifecycleState>
        get() = lifecycle
    val state: StateStore
    val stateStore: StateStore
    val events: SharedFlow<DeviceEvent>

    suspend fun execute(action: DeviceAction): ActionResult

    suspend fun executeAction(action: DeviceAction): ActionResult = execute(action)

    /** Registers the handler used when [DeviceAction.id] matches [id]. */
    fun registerAction(id: String, handler: DeviceActionHandler)

    suspend fun close()
}

/** Runtime view of a BLE device after the BLE layer has established a connection. */
interface DeviceSession : ProtocolSession {
    val services: StateFlow<List<BleService>>

    /** Generic BLE explorer operations kept behind the runtime session boundary. */
    suspend fun read(characteristic: BleCharacteristic): ByteArray

    suspend fun write(characteristic: BleCharacteristic, data: ByteArray, withResponse: Boolean = true)

    /** Bridges BLE notifications into the session event bus. */
    fun notifications(characteristic: BleCharacteristic): Flow<ByteArray>

    suspend fun emitScriptEvent(name: String, value: StateValue)
}

/** Default Phase 2 runtime implementation. */
class DefaultDeviceRuntime(
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val actionHandlers: Map<String, DeviceActionHandler> = emptyMap(),
) : DeviceRuntime {
    override suspend fun open(device: BleDevice, connection: BleConnection): DeviceSession {
        val session = DeviceSessionImpl(device, connection, clock, dispatcher)
        actionHandlers.forEach { (id, handler) -> session.registerAction(id, handler) }
        session.initialize()
        return session
    }
}

/** Alias retained as a concise production-facing name. */
typealias DeviceRuntimeImpl = DefaultDeviceRuntime

private class DeviceSessionImpl(
    override val device: BleDevice,
    private val connection: BleConnection,
    private val clock: Clock,
    dispatcher: CoroutineDispatcher,
) : DeviceSession {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val eventBus = EventBus(clock)
    private val stateImpl = InMemoryStateStore(clock)
    private val lifecycleImpl = MutableStateFlow<DeviceLifecycleState>(DeviceLifecycleState.Created)
    private val servicesImpl = MutableStateFlow<List<BleService>>(emptyList())
    private val handlers = ConcurrentHashMap<String, DeviceActionHandler>()
    private val actionMutex = Mutex()
    private var connectionMonitor: Job? = null
    @Volatile private var closed = false

    override val lifecycle: StateFlow<DeviceLifecycleState> = lifecycleImpl.asStateFlow()
    override val state: StateStore = stateImpl
    override val stateStore: StateStore = stateImpl
    override val services: StateFlow<List<BleService>> = servicesImpl.asStateFlow()
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

    suspend fun initialize() {
        transition(DeviceLifecycleState.Connected)
        transition(DeviceLifecycleState.Initializing)
        connectionMonitor = scope.launch {
            connection.state
                .filter { it is ConnectionState.Disconnected || it is ConnectionState.Error }
                .collectLatest { state ->
                    if (!closed) {
                        when (state) {
                            is ConnectionState.Error -> {
                                transition(DeviceLifecycleState.Error(RuntimeError.ConnectionFailed(state.error.message)))
                                closeAfterFailure()
                            }
                            else -> {
                                transition(DeviceLifecycleState.Disconnected)
                                closeAfterFailure()
                            }
                        }
                    }
                }
        }

        try {
            servicesImpl.value = connection.discoverServices()
            if (closed) throw IllegalStateException("Device disconnected during initialization")
            transition(DeviceLifecycleState.Ready)
            eventBus.emit(DeviceEvent.DeviceReady(device.id, now()))
        } catch (cancelled: CancellationException) {
            closeAfterFailure()
            throw cancelled
        } catch (error: Throwable) {
            transition(DeviceLifecycleState.Error(RuntimeError.InitializationFailed(error.message ?: "Device initialization failed")))
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

    override suspend fun read(characteristic: BleCharacteristic): ByteArray {
        val data = connection.read(characteristic)
        eventBus.emit(DeviceEvent.GattRead(device.id, now(), characteristic, data.clone()))
        return data
    }

    override suspend fun write(characteristic: BleCharacteristic, data: ByteArray, withResponse: Boolean) {
        connection.write(characteristic, data, withResponse)
        eventBus.emit(DeviceEvent.GattWritten(device.id, now(), characteristic, data.clone(), withResponse))
    }

    override fun notifications(characteristic: BleCharacteristic): Flow<ByteArray> = flow {
        connection.notifications(characteristic).collect { data ->
            val snapshot = data.clone()
            eventBus.emit(DeviceEvent.NotificationReceived(device.id, now(), characteristic, snapshot))
            emit(snapshot)
        }
    }

    override suspend fun emitScriptEvent(name: String, value: StateValue) {
        require(name.isNotBlank())
        eventBus.emit(DeviceEvent.ScriptEmitted(device.id, now(), name, value))
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        transition(DeviceLifecycleState.Disconnecting)
        connectionMonitor?.cancel()
        try {
            connection.disconnect()
        } finally {
            transition(DeviceLifecycleState.Disconnected)
            scope.cancel()
        }
    }

    private suspend fun closeAfterFailure() {
        if (closed) return
        closed = true
        connectionMonitor?.cancel()
        runCatching { connection.disconnect() }
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

    private fun now(): Instant = clock.instant()
}

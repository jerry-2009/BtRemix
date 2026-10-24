package com.Fusion.Btremix.definition.session

import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleConnection
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.classic.api.RfcommConnection
import com.Fusion.Btremix.definition.api.InitializationStepDefinition
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.TransportDefinition
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.DeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceSession
import com.Fusion.Btremix.device.runtime.InitializationDiagnostic
import com.Fusion.Btremix.device.runtime.ProtocolSession
import com.Fusion.Btremix.device.runtime.RuntimeError
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.device.runtime.StreamDeviceSession
import com.Fusion.Btremix.protocol.api.DefaultProtocolRuntime
import com.Fusion.Btremix.protocol.api.ProtocolRuntime
import com.Fusion.Btremix.protocol.api.ProtocolTransport
import com.Fusion.Btremix.protocol.api.SessionIo
import com.Fusion.Btremix.protocol.api.SessionRequest
import com.Fusion.Btremix.protocol.api.StreamCodecRegistry
import com.Fusion.Btremix.protocol.api.TogglingSequenceGenerator
import com.Fusion.Btremix.protocol.api.TransactionResult
import com.Fusion.Btremix.protocol.extension.ProtocolExtensionRegistry
import com.Fusion.Btremix.scripting.api.DefaultScriptDriver
import com.Fusion.Btremix.scripting.runtime.bindScripts
import com.Fusion.Btremix.scripting.runtime.bindStreamScripts
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/** Raised when a Definition could not be attached to an otherwise connected session. */
class DefinitionSessionException(
    val definitionId: String,
    val error: RuntimeError,
    cause: Throwable? = null,
) : IllegalStateException("${error.code}: ${error.message}", cause)

/**
 * Opens a runtime session and wires a Definition while keeping protocol-controlled initialization
 * between `Initializing` and `Ready`.
 */
class DefinitionSessionFactory(
    private val runtime: DeviceRuntime = DefaultDeviceRuntime(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: Clock = Clock.systemUTC(),
    private val extensionRegistry: ProtocolExtensionRegistry = ProtocolExtensionRegistry(),
) {
    suspend fun open(
        device: BleDevice,
        connection: BleConnection,
        definition: LoadedDeviceDefinition,
    ): DeviceSession = attach(
        runtime.openForDefinitionBinding(device, connection),
        definition,
    )

    /**
     * Attaches to an existing GATT session. Sessions opened with a ready lifecycle keep the existing
     * v1-v4 behavior; new required steps still fail closed if they cannot complete.
     */
    suspend fun attach(session: DeviceSession, definition: LoadedDeviceDefinition): DeviceSession {
        var bindings: DefinitionBindings? = null
        var triggers: TriggerRuntime? = null
        var sessionIo: DefinitionSessionIo? = null
        var protocolExtensions: ProtocolExtensionSession? = null
        try {
            seedStates(session, definition)
            session.beginInitialization()
            val transport = definition.protocol.transport
            if (transport != null) {
                val characteristic = characteristic(session, transport)
                val protocolTransport = GattProtocolTransport(
                    session,
                    characteristic,
                    transport.withResponse,
                )
                val protocolRuntime = DefaultProtocolRuntime(
                    transport = protocolTransport,
                    packetEncoder = definition.protocolFactory().packetCodec,
                    packetDecoder = definition.protocolFactory().packetCodec,
                )
                sessionIo = DefinitionSessionIo(
                    definition = definition,
                    factory = definition.protocolFactory(),
                    runtime = protocolRuntime,
                    incoming = session.notifications(characteristic),
                    lifecycle = session.lifecycle,
                    writeRaw = { bytes -> session.write(characteristic, bytes, transport.withResponse) },
                    dispatcher = dispatcher,
                )
            }
            protocolExtensions = sessionIo?.let { io ->
                ProtocolExtensionSession(definition, extensionRegistry, io)
            }
            val driver = session.bindScripts(definition, sessionIo, protocolExtensions)
            val gattBindings = DefinitionBindings(
                session = session,
                definition = definition,
                sessionIo = sessionIo,
                dispatcher = dispatcher,
            )
            gattBindings.start()
            bindings = gattBindings
            triggers = sessionIo?.let { io ->
                TriggerRuntime(session, definition, io, driver).also(TriggerRuntime::start)
            }
            runInitialization(session, definition, sessionIo, driver)
            session.markReady()
            return BoundDefinitionSession(
                session,
                gattBindings,
                triggers,
                protocolExtensions,
                sessionIo,
            )
        } catch (cancelled: CancellationException) {
            triggers?.close()
            bindings?.close()
            protocolExtensions?.close()
            sessionIo?.close()
            closeQuietly(session)
            throw cancelled
        } catch (error: Throwable) {
            triggers?.close()
            bindings?.close()
            protocolExtensions?.close()
            sessionIo?.close()
            closeQuietly(session)
            val runtimeError = (error as? RequiredInitializationException)?.error
                ?: RuntimeError.DefinitionBindingFailed(
                    definitionId = definition.id,
                    message = error.message ?: "Failed to attach definition '${definition.id}'",
                    cause = error,
                )
            throw DefinitionSessionException(definition.id, runtimeError, error)
        }
    }

    /** Opens a classic-Bluetooth stream, wires JSON programs, then completes initialization. */
    suspend fun openStream(
        device: BleDevice,
        connection: RfcommConnection,
        definition: LoadedDeviceDefinition,
    ): ProtocolSession {
        val framing = definition.protocol.framing
            ?: error("Definition '${definition.id}' has no protocol.framing")
        val codec = StreamCodecRegistry.create(framing.codec, framing.toSpec())
            ?: error("Unknown framing codec '${framing.codec}'")
        val session = StreamDeviceSession(device, connection, codec, clock, dispatcher)
        return attachStream(session, definition)
    }

    private suspend fun attachStream(
        session: StreamDeviceSession,
        definition: LoadedDeviceDefinition,
    ): ProtocolSession {
        var bindings: StreamDefinitionBindings? = null
        var triggers: TriggerRuntime? = null
        var sessionIo: DefinitionSessionIo? = null
        var protocolExtensions: ProtocolExtensionSession? = null
        try {
            seedStates(session, definition)
            session.initializeForBinding()
            session.beginInitialization()
            val protocolRuntime = DefaultProtocolRuntime(
                transport = session.transport,
                packetEncoder = definition.protocolFactory().packetCodec,
                packetDecoder = definition.protocolFactory().packetCodec,
                sequenceGenerator = definition.protocol.framing?.sequence?.createGenerator()
                    ?: TogglingSequenceGenerator(),
            )
            val io = DefinitionSessionIo(
                definition = definition,
                factory = definition.protocolFactory(),
                runtime = protocolRuntime,
                incoming = session.transport.frames(),
                lifecycle = session.lifecycle,
                writeRaw = session.transport::write,
                dispatcher = dispatcher,
            )
            sessionIo = io
            protocolExtensions = ProtocolExtensionSession(definition, extensionRegistry, io)
            val driver = session.bindStreamScripts(definition, io, protocolExtensions)
            val streamBindings = StreamDefinitionBindings(session, definition, io, dispatcher)
            streamBindings.start()
            bindings = streamBindings
            triggers = TriggerRuntime(session, definition, io, driver).also(TriggerRuntime::start)
            runInitialization(session, definition, io, driver)
            session.markReady()
            streamBindings.startLegacyInitialization()
            return BoundStreamSession(
                session,
                streamBindings,
                triggers,
                protocolExtensions,
                io,
            )
        } catch (cancelled: CancellationException) {
            triggers?.close()
            bindings?.close()
            protocolExtensions?.close()
            sessionIo?.close()
            closeQuietly(session)
            throw cancelled
        } catch (error: Throwable) {
            triggers?.close()
            bindings?.close()
            protocolExtensions?.close()
            sessionIo?.close()
            closeQuietly(session)
            val runtimeError = (error as? RequiredInitializationException)?.error
                ?: RuntimeError.DefinitionBindingFailed(
                    definitionId = definition.id,
                    message = error.message ?: "Failed to attach definition '${definition.id}'",
                    cause = error,
                )
            throw DefinitionSessionException(definition.id, runtimeError, error)
        }
    }

    private suspend fun runInitialization(
        session: ProtocolSession,
        definition: LoadedDeviceDefinition,
        sessionIo: SessionIo?,
        driver: DefaultScriptDriver,
    ) {
        definition.protocol.initialization.forEachIndexed { index, step ->
            val failure: Throwable? = try {
                executeInitializationStep(session, definition, sessionIo, driver, step)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                error
            }
            if (failure != null) {
                val error = RuntimeError.InitializationFailed(
                    "Initialization step $index (${step.label}) failed: ${failure.message ?: failure::class.simpleName}",
                )
                if (step.required) {
                    session.failInitialization(error)
                    throw RequiredInitializationException(error)
                }
                session.recordInitializationFailure(InitializationDiagnostic(step.label, false, error))
            }
        }
    }

    private suspend fun executeInitializationStep(
        session: ProtocolSession,
        definition: LoadedDeviceDefinition,
        sessionIo: SessionIo?,
        driver: DefaultScriptDriver,
        step: InitializationStepDefinition,
    ) {
        when {
            step.transaction != null -> {
                val result = requireNotNull(sessionIo) {
                    "Initialization transaction '${step.transaction}' requires protocol.transport"
                }.request(SessionRequest(step.transaction))
                if (result is TransactionResult.Failure) throw result.errorException()
            }
            step.action != null -> {
                val action = requireNotNull(definition.actions[step.action]) {
                    "Unknown initialization action '${step.action}'"
                }
                val program = requireNotNull(action.script) {
                    "Initialization action '${step.action}' must use a JSON program"
                }
                val result = driver.execute(program, DeviceAction(action.id))
                if (result is ActionResult.Failure) throw result.errorException()
            }
            step.program != null -> {
                val result = driver.execute(step.program, DeviceAction("initialization"))
                if (result is ActionResult.Failure) throw result.errorException()
            }
        }
    }

    private suspend fun seedStates(session: ProtocolSession, definition: LoadedDeviceDefinition) {
        definition.states.forEach { (key, model) ->
            val default = model.defaultValue ?: return@forEach
            session.state.set(key, default, StateSource.INITIAL)
        }
    }

    private fun characteristic(session: DeviceSession, transport: TransportDefinition): BleCharacteristic {
        val serviceId = UUID.fromString(transport.service)
        val characteristicId = UUID.fromString(
            requireNotNull(transport.characteristic) { "GATT transport requires 'characteristic'" },
        )
        return session.services.value
            .firstOrNull { it.uuid == serviceId }
            ?.characteristics
            ?.firstOrNull { it.uuid == characteristicId }
            ?: throw IllegalArgumentException(
                "Characteristic $characteristicId is not available in service $serviceId",
            )
    }

    private suspend fun closeQuietly(session: ProtocolSession) {
        runCatching { session.close() }
    }
}

private class RequiredInitializationException(
    val error: RuntimeError,
) : IllegalStateException(error.message)

private fun TransactionResult.Failure.errorException(): Throwable =
    IllegalStateException(error.message)

private fun ActionResult.Failure.errorException(): Throwable =
    IllegalStateException(error.message, error.cause)

private val InitializationStepDefinition.label: String
    get() = transaction?.let { "transaction:$it" }
        ?: action?.let { "action:$it" }
        ?: "program"

/** Routes GATT protocol I/O through the session and its event bus. */
private class GattProtocolTransport(
    private val session: DeviceSession,
    private val characteristic: BleCharacteristic,
    private val withResponse: Boolean,
) : ProtocolTransport {
    override suspend fun write(data: ByteArray) =
        session.write(characteristic, data, withResponse)

    override fun notifications(): Flow<ByteArray> =
        session.notifications(characteristic)
}

private class BoundDefinitionSession(
    private val delegate: DeviceSession,
    private val bindings: DefinitionBindings,
    private val triggers: TriggerRuntime?,
    private val protocolExtensions: ProtocolExtensionSession?,
    private val sessionIo: DefinitionSessionIo?,
) : DeviceSession by delegate {
    override suspend fun close() {
        triggers?.close()
        bindings.close()
        protocolExtensions?.close()
        sessionIo?.close()
        delegate.close()
    }
}

private class BoundStreamSession(
    private val delegate: StreamDeviceSession,
    private val bindings: StreamDefinitionBindings,
    private val triggers: TriggerRuntime?,
    private val protocolExtensions: ProtocolExtensionSession?,
    private val sessionIo: DefinitionSessionIo?,
) : ProtocolSession by delegate {
    override suspend fun close() {
        triggers?.close()
        bindings.close()
        protocolExtensions?.close()
        sessionIo?.close()
        delegate.close()
    }
}

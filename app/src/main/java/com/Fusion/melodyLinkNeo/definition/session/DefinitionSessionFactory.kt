package com.fusion.melodyLinkNeo.definition.session

import com.fusion.melodyLinkNeo.core.bluetooth.api.BleConnection
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.core.classic.api.RfcommConnection
import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.device.runtime.DefaultDeviceRuntime
import com.fusion.melodyLinkNeo.device.runtime.DeviceRuntime
import com.fusion.melodyLinkNeo.device.runtime.DeviceSession
import com.fusion.melodyLinkNeo.device.runtime.ProtocolSession
import com.fusion.melodyLinkNeo.device.runtime.RuntimeError
import com.fusion.melodyLinkNeo.device.runtime.StateSource
import com.fusion.melodyLinkNeo.device.runtime.StreamDeviceSession
import com.fusion.melodyLinkNeo.protocol.api.StreamCodecRegistry
import com.fusion.melodyLinkNeo.scripting.runtime.bindScripts
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Raised when a Definition could not be attached to an otherwise connected session. */
class DefinitionSessionException(
    val definitionId: String,
    val error: RuntimeError,
    cause: Throwable? = null,
) : IllegalStateException("${error.code}: ${error.message}", cause)

/**
 * Opens a [DeviceSession] for a matched Definition.
 *
 * This is the wiring layer between the Definition runtime and the Device runtime: it lives above
 * both so `device/runtime` never has to depend on `scripting` (scripts already depend on sessions).
 * Opening runs in the order the milestone asks for:
 *
 * 1. the underlying runtime connects and discovers services (existing behaviour),
 * 2. every `states` default is seeded as [StateSource.INITIAL],
 * 3. action scripts are bound with `bindScripts`.
 *
 * A binding failure closes the session and surfaces as [DefinitionSessionException] so it can never
 * be silent. Note that milestone 2 deliberately keeps the Definition schema untouched: only actions
 * carrying a `script` become executable.
 */
class DefinitionSessionFactory(
    private val runtime: DeviceRuntime = DefaultDeviceRuntime(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun open(
        device: BleDevice,
        connection: BleConnection,
        definition: LoadedDeviceDefinition,
    ): DeviceSession = attach(runtime.open(device, connection), definition)

    /**
     * Attaches a Definition to an already-connected session.
     *
     * Used when a device is only recognised after service discovery: the existing session (and its
     * discovered services) is reused instead of reconnecting, and the same seeding/binding steps run.
     */
    suspend fun attach(session: DeviceSession, definition: LoadedDeviceDefinition): DeviceSession {
        try {
            seedStates(session, definition)
            session.bindScripts(definition)
            val bindings = DefinitionBindings(session, definition, dispatcher)
            bindings.start()
            return BoundDefinitionSession(session, bindings)
        } catch (cancelled: CancellationException) {
            closeQuietly(session)
            throw cancelled
        } catch (error: Throwable) {
            closeQuietly(session)
            throw DefinitionSessionException(
                definitionId = definition.id,
                error = RuntimeError.DefinitionBindingFailed(
                    definitionId = definition.id,
                    message = error.message ?: "Failed to attach definition '${definition.id}'",
                    cause = error,
                ),
                cause = error,
            )
        }
    }

    /**
     * Opens a classic-Bluetooth (RFCOMM/SPP) session for a Definition that declares
     * `protocol.transport.type = rfcomm`.
     *
     * The socket is already connected; this seeds state, brings the session to `Ready`, runs the
     * `protocol.initialize` handshake and binds declarative actions/notifications. Definitions with
     * script actions are rejected by the validator for this transport.
     */
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
        try {
            seedStates(session, definition)
            session.initialize()
            val bindings = StreamDefinitionBindings(session, definition, dispatcher)
            bindings.start()
            return BoundStreamSession(session, bindings)
        } catch (cancelled: CancellationException) {
            closeQuietly(session)
            throw cancelled
        } catch (error: Throwable) {
            closeQuietly(session)
            throw DefinitionSessionException(
                definitionId = definition.id,
                error = RuntimeError.DefinitionBindingFailed(
                    definitionId = definition.id,
                    message = error.message ?: "Failed to attach definition '${definition.id}'",
                    cause = error,
                ),
                cause = error,
            )
        }
    }

    private suspend fun seedStates(session: ProtocolSession, definition: LoadedDeviceDefinition) {
        definition.states.forEach { (key, model) ->
            val default = model.defaultValue ?: return@forEach
            session.state.set(key, default, StateSource.INITIAL)
        }
    }

    private suspend fun closeQuietly(session: ProtocolSession) {
        runCatching { session.close() }
    }
}

/**
 * Delegating session that tears down declarative bindings before the underlying session closes,
 * so no notification collector outlives the connection.
 */
private class BoundDefinitionSession(
    private val delegate: DeviceSession,
    private val bindings: DefinitionBindings,
) : DeviceSession by delegate {
    override suspend fun close() {
        bindings.close()
        delegate.close()
    }
}

/** Tears down stream bindings before closing the underlying RFCOMM session. */
private class BoundStreamSession(
    private val delegate: StreamDeviceSession,
    private val bindings: StreamDefinitionBindings,
) : ProtocolSession by delegate {
    override suspend fun close() {
        bindings.close()
        delegate.close()
    }
}

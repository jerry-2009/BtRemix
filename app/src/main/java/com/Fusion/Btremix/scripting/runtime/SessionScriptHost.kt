package com.Fusion.Btremix.scripting.runtime

import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.device.runtime.DeviceSession
import com.Fusion.Btremix.device.runtime.ProtocolSession
import com.Fusion.Btremix.device.runtime.RuntimeError
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.device.runtime.StreamDeviceSession
import com.Fusion.Btremix.protocol.api.SessionIo
import com.Fusion.Btremix.scripting.api.DefaultScriptDriver
import com.Fusion.Btremix.scripting.api.ScriptHost
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Binds validated action scripts to a connected session. */
fun DeviceSession.bindScripts(
    definition: LoadedDeviceDefinition,
    sessionIo: SessionIo? = null,
): DefaultScriptDriver {
    val host = SessionScriptHost(this, definition, sessionIo)
    val driver = DefaultScriptDriver(host)
    registerScriptActions(definition, driver)
    return driver
}

/** Binds a JSON program over the transport-neutral Session I/O (P3 RFCOMM path). */
fun StreamDeviceSession.bindStreamScripts(
    definition: LoadedDeviceDefinition,
    sessionIo: SessionIo,
): DefaultScriptDriver {
    val driver = DefaultScriptDriver(StreamScriptHost(this, definition, sessionIo))
    registerScriptActions(definition, driver)
    return driver
}

private fun ProtocolSession.registerScriptActions(
    definition: LoadedDeviceDefinition,
    driver: DefaultScriptDriver,
) {
    definition.actions.values.forEach { action ->
        action.script?.let { program -> registerAction(action.id) { input -> driver.execute(program, input) } }
    }
}

class SessionScriptHost(
    private val session: DeviceSession,
    private val definition: LoadedDeviceDefinition,
    override val sessionIo: SessionIo? = null,
) : ScriptHost {
    private fun characteristic(service: String, characteristic: String): BleCharacteristic {
        val serviceId = UUID.fromString(service)
        val characteristicId = UUID.fromString(characteristic)
        return session.services.value.firstOrNull { it.uuid == serviceId }
            ?.characteristics?.firstOrNull { it.uuid == characteristicId }
            ?: throw IllegalArgumentException("Characteristic $characteristic is not available in service $service")
    }

    override suspend fun read(service: String, characteristic: String): ByteArray =
        session.read(characteristic(service, characteristic))

    override suspend fun write(service: String, characteristic: String, value: ByteArray, withResponse: Boolean) =
        session.write(characteristic(service, characteristic), value.clone(), withResponse)

    override fun notifications(service: String, characteristic: String): Flow<ByteArray> =
        session.notifications(characteristic(service, characteristic))

    override suspend fun getState(key: String): StateValue? {
        require(key in definition.states) { "Undeclared state '$key'" }
        return session.state.value(key)
    }

    override suspend fun setState(key: String, value: StateValue) {
        require(key in definition.states) { "Undeclared state '$key'" }
        session.state.set(key, value, StateSource.ACTION)
    }

    override suspend fun emit(name: String, value: StateValue) = session.emitScriptEvent(name, value)

    override suspend fun reportError(error: RuntimeError) = session.reportError(error)
}

/**
 * RFCOMM script host. Legacy GATT primitives remain unavailable because this transport has no
 * service/characteristic model; protocol work goes exclusively through SessionIo.
 */
private class StreamScriptHost(
    private val session: StreamDeviceSession,
    private val definition: LoadedDeviceDefinition,
    override val sessionIo: SessionIo,
) : ScriptHost {
    override suspend fun read(service: String, characteristic: String): ByteArray =
        throw IllegalArgumentException("ble.read is not available on a byte-stream session")

    override suspend fun write(
        service: String,
        characteristic: String,
        value: ByteArray,
        withResponse: Boolean,
    ) {
        throw IllegalArgumentException("ble.write is not available on a byte-stream session")
    }

    override fun notifications(service: String, characteristic: String): Flow<ByteArray> =
        throw IllegalArgumentException("ble.subscribe is not available on a byte-stream session")

    override suspend fun getState(key: String): StateValue? {
        require(key in definition.states) { "Undeclared state '$key'" }
        return session.state.value(key)
    }

    override suspend fun setState(key: String, value: StateValue) {
        require(key in definition.states) { "Undeclared state '$key'" }
        session.state.set(key, value, StateSource.ACTION)
    }

    override suspend fun emit(name: String, value: StateValue) = session.emitScriptEvent(name, value)

    override suspend fun reportError(error: RuntimeError) = session.reportError(error)
}

package com.fusion.melodyLinkNeo.scripting.runtime

import com.fusion.melodyLinkNeo.core.bluetooth.api.BleCharacteristic
import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.device.runtime.DeviceSession
import com.fusion.melodyLinkNeo.device.runtime.StateSource
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import com.fusion.melodyLinkNeo.scripting.api.DefaultScriptDriver
import com.fusion.melodyLinkNeo.scripting.api.ScriptHost
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Binds validated action scripts to a connected session. */
fun DeviceSession.bindScripts(definition: LoadedDeviceDefinition) {
    val host = SessionScriptHost(this, definition)
    val driver = DefaultScriptDriver(host)
    definition.actions.values.forEach { action ->
        action.script?.let { program -> registerAction(action.id) { input -> driver.execute(program, input) } }
    }
}

class SessionScriptHost(
    private val session: DeviceSession,
    private val definition: LoadedDeviceDefinition,
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
}

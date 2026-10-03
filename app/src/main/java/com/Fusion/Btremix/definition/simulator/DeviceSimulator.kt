package com.Fusion.Btremix.definition.simulator

import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristicProperty
import com.Fusion.Btremix.core.bluetooth.api.BleConnection
import com.Fusion.Btremix.core.bluetooth.api.BleService
import com.Fusion.Btremix.core.bluetooth.api.ConnectionState
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.session.DefinitionSessionFactory
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceSession
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.SimplePacketCodec
import com.Fusion.Btremix.scripting.api.ScriptStep
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One write the simulator observed, surfaced so a definition author can see what the UI sent. */
data class SimulatorWrite(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val data: ByteArray,
    val withResponse: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is SimulatorWrite &&
        serviceUuid == other.serviceUuid && characteristicUuid == other.characteristicUuid &&
        withResponse == other.withResponse && data.contentEquals(other.data)

    override fun hashCode(): Int =
        31 * (31 * (31 * serviceUuid.hashCode() + characteristicUuid.hashCode()) + withResponse.hashCode()) + data.contentHashCode()
}

/** A characteristic the simulator can inject notifications into. */
data class SimulatorNotifyTarget(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val label: String,
)

/**
 * Runs a Definition against an in-memory fake device so an author can preview UI and protocol logic
 * without hardware. It reuses the production session factory, binding layer and script runtime, so a
 * simulation exercises the same code path as a real connection.
 *
 * Writes are recorded; a write whose command matches a declared transaction is auto-acknowledged
 * with the expected response command so declarative actions complete.
 */
class DeviceSimulator(
    private val definition: LoadedDeviceDefinition,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val connection = SimulatorBleConnection(topologyOf(definition), ::autoAcknowledge)
    private val factory = DefinitionSessionFactory(DefaultDeviceRuntime(clock), dispatcher)
    private val codec = SimplePacketCodec(definition.protocol.packet.includesSequence)
    private val writesImpl = mutableListOf<SimulatorWrite>()

    var session: DeviceSession? = null
        private set

    val writes: List<SimulatorWrite> get() = writesImpl.toList()

    val notifyTargets: List<SimulatorNotifyTarget> = connection.services.flatMap { service ->
        service.characteristics
            .filter { BleCharacteristicProperty.NOTIFY in it.properties || BleCharacteristicProperty.INDICATE in it.properties }
            .map { characteristic ->
                SimulatorNotifyTarget(service.uuid, characteristic.uuid, "${service.uuid} / ${characteristic.uuid}")
            }
    }

    suspend fun start(): DeviceSession {
        stop()
        writesImpl.clear()
        val session = factory.open(SIMULATOR_DEVICE, connection, definition)
        this.session = session
        return session
    }

    suspend fun stop() {
        session?.let { runCatching { it.close() } }
        session = null
    }

    suspend fun injectNotification(target: SimulatorNotifyTarget, data: ByteArray) {
        connection.emit(BleCharacteristic(target.serviceUuid, target.characteristicUuid), data)
    }

    private suspend fun autoAcknowledge(write: SimulatorWrite) {
        writesImpl += write
        val request = runCatching { codec.decode(write.data) }.getOrNull() ?: return
        val expected = definition.protocol.transactions.values
            .firstOrNull { it.requestCommand == request.command && it.expectedCommand != null }
            ?.expectedCommand
            ?: return
        connection.emit(
            BleCharacteristic(write.serviceUuid, write.characteristicUuid),
            codec.encode(Packet(expected, request.sequence, request.payload)),
        )
    }

    private companion object {
        val SIMULATOR_DEVICE = com.Fusion.Btremix.core.bluetooth.api.BleDevice("SIM:00", "Simulated device")
    }
}

/** Builds the fake GATT topology a definition needs, from transport, notify and script steps. */
internal fun topologyOf(definition: LoadedDeviceDefinition): List<BleService> {
    val characteristics = linkedMapOf<UUID, LinkedHashMap<UUID, MutableSet<BleCharacteristicProperty>>>()
    fun declare(service: String, characteristic: String, vararg properties: BleCharacteristicProperty) {
        val serviceUuid = runCatching { UUID.fromString(service) }.getOrNull() ?: return
        val characteristicUuid = runCatching { UUID.fromString(characteristic) }.getOrNull() ?: return
        val byUuid = characteristics.getOrPut(serviceUuid) { linkedMapOf() }
        val props = byUuid.getOrPut(characteristicUuid) { mutableSetOf() }
        props += properties
    }

    definition.protocol.transport?.let {
        it.characteristic?.let { characteristic ->
            declare(it.service, characteristic, BleCharacteristicProperty.WRITE, BleCharacteristicProperty.NOTIFY)
        }
    }
    definition.states.values.forEach { state ->
        state.notify?.let { notify ->
            val service = notify.service
            val characteristic = notify.characteristic
            if (service != null && characteristic != null) {
                declare(service, characteristic, BleCharacteristicProperty.NOTIFY, BleCharacteristicProperty.READ)
            }
        }
    }
    definition.actions.values.forEach { action ->
        action.script?.let { program ->
            fun visit(steps: List<ScriptStep>) {
                steps.forEach { step ->
                    when (step) {
                        is ScriptStep.Read -> declare(step.service, step.characteristic, BleCharacteristicProperty.READ)
                        is ScriptStep.Write -> declare(
                            step.service,
                            step.characteristic,
                            BleCharacteristicProperty.WRITE,
                            BleCharacteristicProperty.NOTIFY,
                        )
                        is ScriptStep.Subscribe -> declare(step.service, step.characteristic, BleCharacteristicProperty.NOTIFY)
                        is ScriptStep.If -> { visit(step.thenSteps); visit(step.elseSteps) }
                        is ScriptStep.Repeat -> visit(step.steps)
                        else -> Unit
                    }
                }
            }
            visit(program.steps)
        }
    }
    return characteristics.map { (serviceUuid, byUuid) ->
        BleService(
            uuid = serviceUuid,
            characteristics = byUuid.map { (characteristicUuid, props) ->
                BleCharacteristic(serviceUuid, characteristicUuid, props.toSet())
            },
        )
    }
}

/** Minimal in-memory [BleConnection] used only by [DeviceSimulator]. */
private class SimulatorBleConnection(
    overrideServices: List<BleService>,
    private val onWrite: suspend (SimulatorWrite) -> Unit,
) : BleConnection {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    private val notificationFlows = mutableMapOf<String, MutableSharedFlow<ByteArray>>()

    val services: List<BleService> = overrideServices

    override val state = mutableState.asStateFlow()

    override suspend fun discoverServices(): List<BleService> = services

    override suspend fun read(characteristic: BleCharacteristic): ByteArray = byteArrayOf()

    override suspend fun write(characteristic: BleCharacteristic, data: ByteArray, withResponse: Boolean) {
        onWrite(SimulatorWrite(characteristic.serviceUuid, characteristic.uuid, data.clone(), withResponse))
    }

    override fun notifications(characteristic: BleCharacteristic): Flow<ByteArray> = flowFor(characteristic)

    override suspend fun disconnect() {
        mutableState.value = ConnectionState.Disconnected
    }

    suspend fun emit(characteristic: BleCharacteristic, data: ByteArray) {
        flowFor(characteristic).emit(data.clone())
    }

    private fun flowFor(characteristic: BleCharacteristic): MutableSharedFlow<ByteArray> =
        synchronized(notificationFlows) {
            notificationFlows.getOrPut("${characteristic.serviceUuid}/${characteristic.uuid}") {
                MutableSharedFlow(replay = 1, extraBufferCapacity = 16)
            }
        }
}

package com.Fusion.Btremix.definition.session

import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.definition.api.ActionDefinition
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.NotifyDefinition
import com.Fusion.Btremix.definition.api.TransportDefinition
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.device.runtime.DeviceSession
import com.Fusion.Btremix.device.runtime.RuntimeError
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.protocol.api.DefaultProtocolRuntime
import com.Fusion.Btremix.protocol.api.ProtocolRuntime
import com.Fusion.Btremix.protocol.api.ProtocolTransport
import com.Fusion.Btremix.protocol.api.TransactionResult
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Bridges a Definition's declarative section onto a live session.
 *
 * Version 2 definitions can describe two bindings without a script:
 *
 * - `states.*.notify` keeps a **continuous** notification subscription that feeds the state store
 *   with [StateSource.NOTIFICATION];
 * - `actions.*.transaction` encodes the action arguments into a protocol message, runs the
 *   request/response transaction and stores the result expression into `resultState`.
 *
 * Scripted actions from the original schema keep working unchanged; the two paths live side by side.
 */
class DefinitionBindings(
    private val session: DeviceSession,
    private val definition: LoadedDeviceDefinition,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val jobs = mutableListOf<Job>()
    private val support = DeclarativeProtocolSupport(definition) { key -> session.state.value(key) }
    private var closed = false

    fun start() {
        bindActions()
        bindNotifications()
        jobs += scope.launch {
            session.lifecycle.collect { lifecycle ->
                when (lifecycle) {
                    DeviceLifecycleState.Disconnected,
                    DeviceLifecycleState.Disconnecting,
                    is DeviceLifecycleState.Error,
                    -> close()
                    else -> Unit
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        jobs.forEach(Job::cancel)
        jobs.clear()
        scope.cancel()
    }

    private fun bindActions() {
        val declarative = definition.actions.values.filter { it.transaction != null }
        if (declarative.isEmpty()) return
        val transportDefinition = requireNotNull(definition.protocol.transport) {
            "Definition '${definition.id}' declares declarative actions but no protocol.transport"
        }
        val characteristic = characteristic(transportDefinition)
        val runtime = DefaultProtocolRuntime(
            transport = SessionProtocolTransport(session, characteristic, transportDefinition.withResponse),
            packetEncoder = support.factory.packetCodec,
            packetDecoder = support.factory.packetCodec,
        )
        declarative.forEach { action ->
            session.registerAction(action.id) { input -> executeTransaction(action, input, runtime) }
        }
    }

    private fun bindNotifications() {
        definition.states.values.forEach { state ->
            val notify = state.notify ?: return@forEach
            // Payload-type notifications belong to byte-stream (rfcomm) sessions; the validator
            // already rejects them next to a gatt transport, so a stray one is ignored here.
            if (notify.payloadTypes.isNotEmpty()) return@forEach
            val characteristic = characteristic(notify)
            jobs += scope.launch {
                session.notifications(characteristic).collect { bytes ->
                    val value = support.decodeNotification(state, notify, bytes) ?: return@collect
                    session.state.set(state.key, value, StateSource.NOTIFICATION)
                }
            }
        }
    }

    private fun characteristic(notify: NotifyDefinition): BleCharacteristic = characteristic(
        requireNotNull(notify.service) { "GATT notify requires 'service'" },
        requireNotNull(notify.characteristic) { "GATT notify requires 'characteristic'" },
    )

    private fun characteristic(transport: TransportDefinition): BleCharacteristic = characteristic(
        transport.service,
        requireNotNull(transport.characteristic) { "GATT transport requires 'characteristic'" },
    )

    private fun characteristic(service: String, characteristic: String): BleCharacteristic {
        val serviceId = UUID.fromString(service)
        val characteristicId = UUID.fromString(characteristic)
        return session.services.value
            .firstOrNull { it.uuid == serviceId }
            ?.characteristics
            ?.firstOrNull { it.uuid == characteristicId }
            ?: throw IllegalArgumentException("Characteristic $characteristic is not available in service $service")
    }

    private suspend fun executeTransaction(
        action: ActionDefinition,
        input: DeviceAction,
        runtime: ProtocolRuntime,
    ): ActionResult {
        val transactionName = requireNotNull(action.transaction)
        val transaction = definition.protocol.transactions[transactionName]
            ?: return ActionResult.Failure(RuntimeError.ActionNotFound("${action.id}:$transactionName"))
        val message = transaction.requestMessage?.let { name ->
            support.buildMessage(action, name, input)
        }
        val result = runtime.execute(support.factory.transaction(transactionName, message))
        return when (result) {
            is TransactionResult.Success -> {
                val value = support.resultValue(action, input)
                if (action.resultState != null && value != null) {
                    session.state.set(action.resultState, value, StateSource.ACTION)
                }
                ActionResult.Success(value)
            }
            is TransactionResult.Failure -> ActionResult.Failure(
                RuntimeError.ActionFailed(
                    action.id,
                    "Transaction '$transactionName' failed: ${result.error.message}",
                ),
            )
        }
    }
}

/** Routes protocol writes and notification reads through the session and its event bus. */
private class SessionProtocolTransport(
    private val session: DeviceSession,
    private val characteristic: BleCharacteristic,
    private val withResponse: Boolean,
) : ProtocolTransport {
    override suspend fun write(data: ByteArray) = session.write(characteristic, data, withResponse)

    override fun notifications(): Flow<ByteArray> = session.notifications(characteristic)
}

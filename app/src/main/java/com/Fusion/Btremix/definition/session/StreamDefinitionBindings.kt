package com.Fusion.Btremix.definition.session

import com.Fusion.Btremix.definition.api.ActionDefinition
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.device.runtime.RuntimeError
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.device.runtime.StreamDeviceSession
import com.Fusion.Btremix.protocol.api.SessionIo
import com.Fusion.Btremix.protocol.api.SessionRequest
import com.Fusion.Btremix.protocol.api.TransactionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Bridges a Definition's declarative section onto a classic-Bluetooth byte-stream session.
 *
 * Compared with [DefinitionBindings] the transport is a raw RFCOMM stream instead of a GATT
 * characteristic, so:
 *
 * - transactions are matched by `expectedPayloadTypes` (such peers number responses independently of
 *   the request);
 * - notifications are selected by `notify.payloadType` rather than by characteristic;
 * - `protocol.initialize` runs the declared handshake transactions once, in order, without blocking
 *   the UI when a device ignores one of them.
 */
class StreamDefinitionBindings(
    private val session: StreamDeviceSession,
    private val definition: LoadedDeviceDefinition,
    private val sessionIo: SessionIo,
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
        definition.actions.values.filter { it.transaction != null }.forEach { action ->
            session.registerAction(action.id) { input -> executeTransaction(action, input) }
        }
    }

    private fun bindNotifications() {
        definition.states.values.forEach { state ->
            val notify = state.notify ?: return@forEach
            if (notify.payloadTypes.isEmpty() && notify.matcher == null) return@forEach
            jobs += scope.launch {
                session.packets().collect { packet ->
                    if (!support.matchesNotification(packet, notify)) return@collect
                    val value = support.decodeNotification(state, notify, packet.payload) ?: return@collect
                    session.state.set(state.key, value, StateSource.NOTIFICATION)
                }
            }
        }
    }

    /** Legacy v1-v4 best-effort initialization, deliberately run only after Ready. */
    fun startLegacyInitialization() {
        val names = definition.protocol.initialize
        if (names.isEmpty()) return
        jobs += scope.launch {
            names.forEach { name ->
                // A device that ignores one handshake step must not block the rest of the flow.
                runCatching { sessionIo.request(SessionRequest(name)) }
            }
        }
    }

    private suspend fun executeTransaction(action: ActionDefinition, input: DeviceAction): ActionResult {
        val transactionName = requireNotNull(action.transaction)
        val transaction = definition.protocol.transactions[transactionName]
            ?: return ActionResult.Failure(RuntimeError.ActionNotFound("${action.id}:$transactionName"))
        val message = transaction.requestMessage?.let { name -> support.buildMessage(action, name, input) }
        if (!transaction.expectsResponse) {
            // The peer only acknowledges this command at the framing layer; do not wait for a payload.
            sessionIo.send(
                SessionRequest(
                    endpoint = transactionName,
                    packet = support.factory.encodePacket(transactionName, message),
                ),
            )
            val value = support.resultValue(action, input)
            if (action.resultState != null && value != null) {
                session.state.set(action.resultState, value, StateSource.ACTION)
            }
            refresh(action)
            return ActionResult.Success(value)
        }
        return when (
            val result = sessionIo.request(
                SessionRequest(
                    endpoint = transactionName,
                    packet = support.factory.encodePacket(transactionName, message),
                    timeout = transaction.timeout,
                ),
            )
        ) {
            is TransactionResult.Success -> {
                val value = support.resultValue(action, input)
                if (action.resultState != null && value != null) {
                    session.state.set(action.resultState, value, StateSource.ACTION)
                }
                refresh(action)
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

    /**
     * Read-after-write for peers that only acknowledge a SET: the declared refresh transactions are
     * issued once the write is done, and their responses update state through the notify bindings.
     * A peer that ignores the read-back must not turn an already-applied write into a failure.
     */
    private suspend fun refresh(action: ActionDefinition) {
        action.refresh.forEach { name ->
            runCatching { sessionIo.request(SessionRequest(name)) }
        }
    }
}

package com.fusion.melodyLinkNeo.definition.session

import com.fusion.melodyLinkNeo.definition.api.ActionDefinition
import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.device.runtime.ActionResult
import com.fusion.melodyLinkNeo.device.runtime.DeviceAction
import com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState
import com.fusion.melodyLinkNeo.device.runtime.RuntimeError
import com.fusion.melodyLinkNeo.device.runtime.StateSource
import com.fusion.melodyLinkNeo.device.runtime.StreamDeviceSession
import com.fusion.melodyLinkNeo.protocol.api.DefaultProtocolRuntime
import com.fusion.melodyLinkNeo.protocol.api.ProtocolRuntime
import com.fusion.melodyLinkNeo.protocol.api.TogglingSequenceGenerator
import com.fusion.melodyLinkNeo.protocol.api.TransactionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val jobs = mutableListOf<Job>()
    private val support = DeclarativeProtocolSupport(definition) { key -> session.state.value(key) }
    private val transactionMutex = Mutex()
    private var closed = false

    private val runtime: ProtocolRuntime = DefaultProtocolRuntime(
        transport = session.transport,
        packetEncoder = support.factory.packetCodec,
        packetDecoder = support.factory.packetCodec,
        sequenceGenerator = TogglingSequenceGenerator(),
    )

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
        startInitialization()
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
            session.registerAction(action.id) { input ->
                transactionMutex.withLock { executeTransaction(action, input) }
            }
        }
    }

    private fun bindNotifications() {
        definition.states.values.forEach { state ->
            val notify = state.notify ?: return@forEach
            val payloadTypes = notify.payloadTypes
            if (payloadTypes.isEmpty()) return@forEach
            jobs += scope.launch {
                session.packets().collect { packet ->
                    val type = packet.payload.firstOrNull()?.toInt()?.and(0xff) ?: return@collect
                    if (type !in payloadTypes) return@collect
                    val value = support.decodeNotification(state, notify, packet.payload) ?: return@collect
                    session.state.set(state.key, value, StateSource.NOTIFICATION)
                }
            }
        }
    }

    private fun startInitialization() {
        val names = definition.protocol.initialize
        if (names.isEmpty()) return
        jobs += scope.launch {
            names.forEach { name ->
                // A device that ignores one handshake step must not block the rest of the flow.
                runCatching { transactionMutex.withLock { runtime.execute(support.factory.transaction(name)) } }
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
            runtime.send(support.factory.encodePacket(transactionName, message))
            val value = support.resultValue(action, input)
            if (action.resultState != null && value != null) {
                session.state.set(action.resultState, value, StateSource.ACTION)
            }
            refresh(action)
            return ActionResult.Success(value)
        }
        return when (val result = runtime.execute(support.factory.transaction(transactionName, message))) {
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
            runCatching { runtime.execute(support.factory.transaction(name)) }
        }
    }
}

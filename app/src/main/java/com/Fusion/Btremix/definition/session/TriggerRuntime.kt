package com.Fusion.Btremix.definition.session

import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.TriggerDefinition
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.ProtocolSession
import com.Fusion.Btremix.device.runtime.RuntimeError
import com.Fusion.Btremix.protocol.api.SessionIo
import com.Fusion.Btremix.protocol.api.SessionSubscription
import com.Fusion.Btremix.scripting.api.ScriptDriver
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binds validated `triggers` to one session instance. There is no vendor branch here: all matching
 * is data and every execution goes back through the controlled Session I/O.
 */
internal class TriggerRuntime(
    private val session: ProtocolSession,
    private val definition: LoadedDeviceDefinition,
    private val sessionIo: SessionIo,
    private val driver: ScriptDriver,
) {
    private val subscriptions = mutableListOf<SessionSubscription>()
    private val closed = AtomicBoolean(false)

    fun start() {
        definition.triggers.values.forEach { trigger ->
            subscriptions += sessionIo.onFrame(trigger.matcher) { execute(trigger) }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        subscriptions.forEach(SessionSubscription::cancel)
        subscriptions.clear()
    }

    private suspend fun execute(trigger: TriggerDefinition) {
        if (closed.get()) return
        val action = DeviceAction("trigger.${trigger.id}")
        val result = when {
            trigger.program != null -> driver.execute(trigger.program, action)
            trigger.action != null -> session.execute(DeviceAction(trigger.action))
            else -> ActionResult.Failure(
                RuntimeError.ScriptFailed(trigger.id, "Trigger has no execution source"),
            )
        }
        if (result is ActionResult.Failure) {
            session.reportError(
                RuntimeError.ScriptFailed(
                    id = "trigger.${trigger.id}",
                    message = "Trigger '${trigger.id}' failed: ${result.error.message}",
                    cause = result.error.cause,
                ),
            )
        }
    }
}

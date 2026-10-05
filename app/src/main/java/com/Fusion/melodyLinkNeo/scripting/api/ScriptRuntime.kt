package com.fusion.melodyLinkNeo.scripting.api

import com.fusion.melodyLinkNeo.device.runtime.ActionResult
import com.fusion.melodyLinkNeo.device.runtime.DeviceAction
import com.fusion.melodyLinkNeo.device.runtime.RuntimeError
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/** Capabilities explicitly granted to a script for one device session. */
interface ScriptHost {
    suspend fun read(service: String, characteristic: String): ByteArray
    suspend fun write(service: String, characteristic: String, value: ByteArray, withResponse: Boolean)
    fun notifications(service: String, characteristic: String): Flow<ByteArray>
    suspend fun getState(key: String): StateValue?
    suspend fun setState(key: String, value: StateValue)
    suspend fun emit(name: String, value: StateValue)
}

data class ScriptLimits(
    val timeout: Duration = Duration.parse("5s"),
    val maxSteps: Int = 512,
    val maxBytes: Int = 4096,
) {
    init { require(timeout.isPositive() && maxSteps > 0 && maxBytes > 0) }
}

class DefaultScriptDriver(
    private val host: ScriptHost,
    private val limits: ScriptLimits = ScriptLimits(),
) : ScriptDriver {
    override suspend fun execute(program: ScriptProgram, action: DeviceAction): ActionResult = try {
        withTimeout(limits.timeout) {
            val variables = action.args.toMutableMap()
            var steps = 0
            var returned: StateValue? = null
            suspend fun eval(expression: ScriptExpression): StateValue =
                evaluateScriptExpression(expression, action.args, variables, limits.maxBytes, host::getState)
            suspend fun run(items: List<ScriptStep>) {
                for (step in items) {
                    if (++steps > limits.maxSteps) throw ScriptException("step limit exceeded")
                    when (step) {
                        is ScriptStep.Read -> variables[step.into] = StateValue.BytesValue(host.read(step.service, step.characteristic).bounded())
                        is ScriptStep.Write -> host.write(step.service, step.characteristic, eval(step.value).bytes(), step.withResponse)
                        is ScriptStep.Subscribe -> variables[step.into] = StateValue.BytesValue(host.notifications(step.service, step.characteristic).first().bounded())
                        is ScriptStep.GetState -> variables[step.into] = host.getState(step.key) ?: throw ScriptException("missing state '${step.key}'")
                        is ScriptStep.SetState -> host.setState(step.key, eval(step.value))
                        is ScriptStep.Emit -> host.emit(step.name, eval(step.value))
                        is ScriptStep.Assign -> variables[step.name] = eval(step.value)
                        is ScriptStep.If -> run(if ((eval(step.condition) as? StateValue.BooleanValue)?.value == true) step.thenSteps else step.elseSteps)
                        is ScriptStep.Repeat -> repeat(step.count) { run(step.steps) }
                        is ScriptStep.Return -> { returned = eval(step.value); return }
                    }
                    if (returned != null) return
                }
            }
            run(program.steps)
            ActionResult.Success(returned)
        }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
        ActionResult.Failure(RuntimeError.ScriptFailed(action.id, "Script timed out", error))
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Throwable) {
        ActionResult.Failure(RuntimeError.ScriptFailed(action.id, error.message ?: "Script failed", error))
    }

    private fun StateValue.bytes(): ByteArray = (this as? StateValue.BytesValue)?.value ?: throw ScriptException("ble.write requires bytes")
    private fun ByteArray.bounded(): ByteArray = also { if (it.size > limits.maxBytes) throw ScriptException("byte limit exceeded") }.clone()
    private class ScriptException(message: String) : IllegalArgumentException(message)
}

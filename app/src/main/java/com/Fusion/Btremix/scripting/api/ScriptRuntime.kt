package com.Fusion.Btremix.scripting.api

import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.RuntimeError
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.protocol.api.SessionIo
import com.Fusion.Btremix.protocol.api.SessionRequest
import com.Fusion.Btremix.protocol.api.SessionSubscription
import com.Fusion.Btremix.protocol.api.TransactionResult
import com.Fusion.Btremix.protocol.extension.ProtocolExtensionException
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
    suspend fun reportError(error: RuntimeError) = Unit

    /**
     * Controlled protocol I/O for new `session.*` opcodes. Legacy script-only definitions can leave
     * this null and continue using their frozen GATT primitives.
     */
    val sessionIo: SessionIo? get() = null

    /** Executes a declared first-party extension. Definitions without extensions leave this absent. */
    suspend fun executeExtension(
        extensionId: String,
        entry: String,
        input: ByteArray,
        timeout: Duration,
    ): ByteArray = throw IllegalArgumentException("Protocol extensions are not available")
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
    override suspend fun execute(program: ScriptProgram, action: DeviceAction): ActionResult =
        executeScoped(program, action, mutableListOf())

    private suspend fun executeScoped(
        program: ScriptProgram,
        action: DeviceAction,
        subscriptions: MutableList<SessionSubscription>,
    ): ActionResult {
        var currentExtension: ScriptStep.ExtensionExecute? = null
        return try {
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
                            is ScriptStep.Read -> {
                                variables[step.into] = StateValue.BytesValue(
                                    host.read(step.service, step.characteristic).bounded(),
                                )
                            }
                            is ScriptStep.Write -> {
                                host.write(step.service, step.characteristic, eval(step.value).bytes(), step.withResponse)
                            }
                            is ScriptStep.Subscribe -> {
                                variables[step.into] = StateValue.BytesValue(
                                    host.notifications(step.service, step.characteristic).first().bounded(),
                                )
                            }
                            is ScriptStep.GetState -> {
                                variables[step.into] = host.getState(step.key)
                                    ?: throw ScriptException("missing state '${step.key}'")
                            }
                            is ScriptStep.SetState -> host.setState(step.key, eval(step.value))
                            is ScriptStep.Emit -> host.emit(step.name, eval(step.value))
                            is ScriptStep.Assign -> variables[step.name] = eval(step.value)
                            is ScriptStep.If -> {
                                run(
                                    if ((eval(step.condition) as? StateValue.BooleanValue)?.value == true) {
                                        step.thenSteps
                                    } else {
                                        step.elseSteps
                                    },
                                )
                            }
                            is ScriptStep.Repeat -> repeat(step.count) { run(step.steps) }
                            is ScriptStep.Return -> {
                                returned = eval(step.value)
                                return
                            }
                            is ScriptStep.SessionSend -> {
                                val bytes = eval(step.value).bytes().bounded()
                                session().send(bytes)
                            }
                            is ScriptStep.SessionRequest -> {
                                val result = session().request(
                                    SessionRequest(
                                        endpoint = step.endpoint,
                                        matcher = step.matcher,
                                        timeout = step.timeout,
                                    ),
                                )
                                val response = when (result) {
                                    is TransactionResult.Success -> result.response
                                    is TransactionResult.Failure ->
                                        throw ScriptException("session.request '${step.endpoint}' failed: ${result.error.message}")
                                }
                                step.into?.let { into ->
                                    variables[into] = StateValue.BytesValue(response.payload.bounded())
                                }
                            }
                            is ScriptStep.SessionWaitFor -> {
                                val packet = session().waitFor(step.matcher, step.timeout)
                                step.into?.let { into ->
                                    variables[into] = StateValue.BytesValue(packet.payload.bounded())
                                }
                            }
                            is ScriptStep.SessionOnFrame -> {
                                val nestedAction = action.copy(id = "${action.id}:onFrame")
                                val subscription = session().onFrame(step.matcher) {
                                    val result = executeScoped(step.program, nestedAction, mutableListOf())
                                    if (result is ActionResult.Failure) host.reportError(result.error)
                                }
                                subscriptions += subscription
                            }
                            is ScriptStep.ExtensionExecute -> {
                                currentExtension = step
                                val input = eval(step.input).bytes().bounded()
                                val output = host.executeExtension(
                                    extensionId = step.extensionId,
                                    entry = step.entry,
                                    input = input,
                                    timeout = step.timeout,
                                ).bounded()
                                step.into?.let { into ->
                                    variables[into] = StateValue.BytesValue(output)
                                }
                            }
                        }
                        if (returned != null) return
                    }
                }
                run(program.steps)
                ActionResult.Success(returned)
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            subscriptions.forEach(SessionSubscription::cancel)
            ActionResult.Failure(RuntimeError.ScriptFailed(action.id, "Script timed out", error))
        } catch (error: ProtocolExtensionException) {
            subscriptions.forEach(SessionSubscription::cancel)
            ActionResult.Failure(
                RuntimeError.ExtensionFailed(
                    extensionId = currentExtension?.extensionId ?: "unknown",
                    entry = currentExtension?.entry ?: "unknown",
                    message = error.message ?: "Protocol extension failed",
                    cause = error,
                ),
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            subscriptions.forEach(SessionSubscription::cancel)
            throw error
        } catch (error: Throwable) {
            subscriptions.forEach(SessionSubscription::cancel)
            ActionResult.Failure(RuntimeError.ScriptFailed(action.id, error.message ?: "Script failed", error))
        }
    }

    private fun session(): SessionIo =
        host.sessionIo ?: throw ScriptException("session I/O is not available for this definition")

    private fun StateValue.bytes(): ByteArray =
        (this as? StateValue.BytesValue)?.value ?: throw ScriptException("operation requires bytes")

    private fun ByteArray.bounded(): ByteArray = also { if (it.size > limits.maxBytes) throw ScriptException("byte limit exceeded") }.clone()
    private class ScriptException(message: String) : IllegalArgumentException(message)
}

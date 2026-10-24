package com.Fusion.Btremix.scripting.api

import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.protocol.api.PacketMatcherSpec
import kotlin.time.Duration

/** A deliberately small interpreted language. Programs never receive Android or JVM objects. */
data class ScriptProgram(val steps: List<ScriptStep>)

sealed interface ScriptExpression {
    data class Literal(val value: StateValue) : ScriptExpression
    data class Argument(val name: String) : ScriptExpression
    data class Variable(val name: String) : ScriptExpression
    data class State(val key: String) : ScriptExpression
    data class ByteAt(val value: ScriptExpression, val index: Int) : ScriptExpression
    data class Equals(val left: ScriptExpression, val right: ScriptExpression) : ScriptExpression
    data class Concat(val values: List<ScriptExpression>) : ScriptExpression
    /** Picks [whenTrue] or [whenFalse] from [condition]; used to derive protocol bytes from a mode. */
    data class Conditional(
        val condition: ScriptExpression,
        val whenTrue: ScriptExpression,
        val whenFalse: ScriptExpression,
    ) : ScriptExpression
    /** Maps a numeric or string value through [table], falling back to the raw value as text. */
    data class Mapping(
        val value: ScriptExpression,
        val table: Map<String, String>,
    ) : ScriptExpression
    /**
     * Byte length of a bytes value. Firmware often answers the same parameter with differently
     * shaped payloads ([value][…]), so a decode can pick the field position from the frame length.
     */
    data class Length(val value: ScriptExpression) : ScriptExpression
}

sealed interface ScriptStep {
    data class Read(val service: String, val characteristic: String, val into: String) : ScriptStep
    data class Write(val service: String, val characteristic: String, val value: ScriptExpression, val withResponse: Boolean = true) : ScriptStep
    data class Subscribe(val service: String, val characteristic: String, val into: String) : ScriptStep
    data class GetState(val key: String, val into: String) : ScriptStep
    data class SetState(val key: String, val value: ScriptExpression) : ScriptStep
    data class Emit(val name: String, val value: ScriptExpression) : ScriptStep
    data class Assign(val name: String, val value: ScriptExpression) : ScriptStep
    data class If(val condition: ScriptExpression, val thenSteps: List<ScriptStep>, val elseSteps: List<ScriptStep>) : ScriptStep
    data class Repeat(val count: Int, val steps: List<ScriptStep>) : ScriptStep
    data class Return(val value: ScriptExpression) : ScriptStep

    /** Sends an already-encoded frame through the controlled session write path. */
    data class SessionSend(val value: ScriptExpression) : ScriptStep

    /** Runs a Definition transaction by its logical endpoint name. */
    data class SessionRequest(
        val endpoint: String,
        val matcher: PacketMatcherSpec? = null,
        val timeout: Duration? = null,
        val into: String? = null,
    ) : ScriptStep

    /** Waits for one matching frame without creating a second transport reader. */
    data class SessionWaitFor(
        val matcher: PacketMatcherSpec,
        val timeout: Duration,
        val into: String? = null,
    ) : ScriptStep

    /** Registers a bounded event program for the lifetime of the session. */
    data class SessionOnFrame(
        val matcher: PacketMatcherSpec,
        val program: ScriptProgram,
    ) : ScriptStep

    /** Calls one explicitly declared first-party extension with bounded input bytes. */
    data class ExtensionExecute(
        val extensionId: String,
        val entry: String,
        val input: ScriptExpression,
        val timeout: Duration,
        val into: String? = null,
    ) : ScriptStep
}

interface ScriptDriver {
    suspend fun execute(program: ScriptProgram, action: DeviceAction): ActionResult
}


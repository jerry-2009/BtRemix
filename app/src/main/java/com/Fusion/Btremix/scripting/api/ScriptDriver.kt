package com.Fusion.Btremix.scripting.api

import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.StateValue

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
}

interface ScriptDriver {
    suspend fun execute(program: ScriptProgram, action: DeviceAction): ActionResult
}


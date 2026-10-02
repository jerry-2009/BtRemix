package com.Fusion.Btremix.scripting.api

import com.Fusion.Btremix.definition.json.JsonValue
import com.Fusion.Btremix.device.runtime.StateValue
import java.util.UUID

class ScriptFormatException(val path: String, message: String) : IllegalArgumentException("$path: $message")

/** Compiles JSON data into an allowlisted program; no source code or reflection is evaluated. */
object ScriptCodec {
    private const val MAX_NODES = 256
    private const val MAX_DEPTH = 8
    private const val MAX_BYTES = 4096

    fun parse(value: JsonValue, path: String): ScriptProgram {
        var nodes = 0
        fun visit() {
            if (++nodes > MAX_NODES) throw ScriptFormatException(path, "script exceeds $MAX_NODES nodes")
        }
        lateinit var expression: (JsonValue, String, Int) -> ScriptExpression
        lateinit var steps: (JsonValue, String, Int) -> List<ScriptStep>
        expression = { input, at, depth ->
            visit()
            if (depth > MAX_DEPTH) throw ScriptFormatException(at, "expression nesting exceeds $MAX_DEPTH")
            when (input) {
                is JsonValue.BooleanValue -> ScriptExpression.Literal(StateValue.BooleanValue(input.value))
                is JsonValue.NumberValue -> ScriptExpression.Literal(number(input.raw, at))
                is JsonValue.StringValue -> ScriptExpression.Literal(StateValue.StringValue(input.value))
                is JsonValue.Object -> {
                    if (input.values.size != 1) throw ScriptFormatException(at, "expression requires exactly one operator")
                    val (op, argument) = input.values.entries.single()
                    when (op) {
                        "arg" -> ScriptExpression.Argument(argument.string(at))
                        "var" -> ScriptExpression.Variable(argument.string(at))
                        "state" -> ScriptExpression.State(argument.string(at))
                        "hex" -> ScriptExpression.Literal(StateValue.BytesValue(hex(argument.string(at), at)))
                        "at" -> {
                            val parts = argument.array(at)
                            if (parts.size != 2) throw ScriptFormatException(at, "at requires value and index")
                            ScriptExpression.ByteAt(expression(parts[0], "$at[0]", depth + 1), parts[1].integer("$at[1]").also {
                                if (it < 0 || it >= MAX_BYTES) throw ScriptFormatException("$at[1]", "index out of range")
                            })
                        }
                        "eq" -> {
                            val parts = argument.array(at)
                            if (parts.size != 2) throw ScriptFormatException(at, "eq requires two values")
                            ScriptExpression.Equals(expression(parts[0], "$at[0]", depth + 1), expression(parts[1], "$at[1]", depth + 1))
                        }
                        "concat" -> ScriptExpression.Concat(argument.array(at).mapIndexed { index, part -> expression(part, "$at[$index]", depth + 1) })
                        else -> throw ScriptFormatException(at, "unsupported expression '$op'")
                    }
                }
                else -> throw ScriptFormatException(at, "unsupported expression")
            }
        }
        steps = { input, at, depth ->
            if (depth > MAX_DEPTH) throw ScriptFormatException(at, "step nesting exceeds $MAX_DEPTH")
            input.array(at).mapIndexed { index, item ->
                visit()
                val stepPath = "$at[$index]"
                val fields = item.obj(stepPath)
                val op = fields.requiredString("op", stepPath)
                fun required(name: String) = fields.requiredString(name, stepPath)
                fun value() = expression(fields.required("value", stepPath), "$stepPath.value", 0)
                fun characteristic() = UUID.fromString(required("characteristic")).toString()
                fun service() = UUID.fromString(required("service")).toString()
                val step = try {
                    when (op) {
                        "ble.read" -> ScriptStep.Read(service(), characteristic(), required("into"))
                        "ble.write" -> ScriptStep.Write(service(), characteristic(), value(), fields.optionalBoolean("withResponse", stepPath) ?: true)
                        "ble.subscribe" -> ScriptStep.Subscribe(service(), characteristic(), required("into"))
                        "state.get" -> ScriptStep.GetState(required("key"), required("into"))
                        "state.set" -> ScriptStep.SetState(required("key"), value())
                        "event.emit" -> ScriptStep.Emit(required("name"), value())
                        "let" -> ScriptStep.Assign(required("name"), value())
                        "if" -> ScriptStep.If(expression(fields.required("condition", stepPath), "$stepPath.condition", 0), steps(fields.required("then", stepPath), "$stepPath.then", depth + 1), fields.values["else"]?.let { steps(it, "$stepPath.else", depth + 1) } ?: emptyList())
                        "repeat" -> ScriptStep.Repeat(fields.required("count", stepPath).integer("$stepPath.count").also { if (it !in 0..32) throw ScriptFormatException("$stepPath.count", "must be 0..32") }, steps(fields.required("steps", stepPath), "$stepPath.steps", depth + 1))
                        "return" -> ScriptStep.Return(value())
                        else -> throw ScriptFormatException("$stepPath.op", "unsupported operation '$op'")
                    }
                } catch (error: IllegalArgumentException) {
                    if (error is ScriptFormatException) throw error
                    throw ScriptFormatException(stepPath, error.message ?: "invalid step")
                }
                step
            }
        }
        return ScriptProgram(steps(value, path, 0))
    }

    private fun number(raw: String, path: String): StateValue = raw.toIntOrNull()?.let(StateValue::IntValue)
        ?: raw.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(StateValue::DoubleValue)
        ?: throw ScriptFormatException(path, "invalid number")

    private fun hex(raw: String, path: String): ByteArray {
        val clean = raw.filterNot(Char::isWhitespace)
        if (clean.length % 2 != 0 || clean.length > MAX_BYTES * 2) throw ScriptFormatException(path, "hex length must be even and at most $MAX_BYTES bytes")
        return try { ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
        catch (_: NumberFormatException) { throw ScriptFormatException(path, "invalid hex") }
    }

    private fun JsonValue.obj(path: String) = this as? JsonValue.Object ?: throw ScriptFormatException(path, "expected object")
    private fun JsonValue.array(path: String) = (this as? JsonValue.Array)?.values ?: throw ScriptFormatException(path, "expected array")
    private fun JsonValue.string(path: String) = (this as? JsonValue.StringValue)?.value?.takeIf(String::isNotBlank) ?: throw ScriptFormatException(path, "expected non-empty string")
    private fun JsonValue.integer(path: String) = (this as? JsonValue.NumberValue)?.raw?.toIntOrNull() ?: throw ScriptFormatException(path, "expected integer")
    private fun JsonValue.Object.required(name: String, path: String) = values[name] ?: throw ScriptFormatException("$path.$name", "required")
    private fun JsonValue.Object.requiredString(name: String, path: String) = required(name, path).string("$path.$name")
    private fun JsonValue.Object.optionalBoolean(name: String, path: String) = values[name]?.let { (it as? JsonValue.BooleanValue)?.value ?: throw ScriptFormatException("$path.$name", "expected boolean") }
}

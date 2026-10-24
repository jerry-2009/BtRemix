package com.Fusion.Btremix.scripting.api

import com.Fusion.Btremix.definition.json.JsonValue
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.protocol.api.MAX_COMMAND
import com.Fusion.Btremix.protocol.api.PacketMatcherSpec
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class ScriptFormatException(val path: String, message: String) : IllegalArgumentException("$path: $message")

/** Compiles JSON data into an allowlisted program; no source code or reflection is evaluated. */
object ScriptCodec {
    private const val MAX_NODES = 256
    private const val MAX_DEPTH = 8
    private const val MAX_BYTES = 4096
    private const val MAX_TIMEOUT_MS = 60_000L

    fun parse(value: JsonValue, path: String): ScriptProgram = ScriptProgram(Parser().steps(value, path, 0))

    /** Parses one expression, used by declarative bindings such as `notify.decode`. */
    fun parseExpression(value: JsonValue, path: String): ScriptExpression = Parser().expression(value, path, 0)

    private class Parser {
        private var nodes = 0

        private fun visit(path: String) {
            if (++nodes > MAX_NODES) throw ScriptFormatException(path, "script exceeds $MAX_NODES nodes")
        }

        fun expression(input: JsonValue, at: String, depth: Int): ScriptExpression {
            visit(at)
            if (depth > MAX_DEPTH) throw ScriptFormatException(at, "expression nesting exceeds $MAX_DEPTH")
            return when (input) {
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
                        "bitAnd" -> {
                            val parts = argument.array(at)
                            if (parts.size != 2) throw ScriptFormatException(at, "bitAnd requires two values")
                            ScriptExpression.BitAnd(
                                expression(parts[0], "$at[0]", depth + 1),
                                expression(parts[1], "$at[1]", depth + 1),
                            )
                        }
                        "concat" -> ScriptExpression.Concat(argument.array(at).mapIndexed { index, part -> expression(part, "$at[$index]", depth + 1) })
                        "len" -> ScriptExpression.Length(expression(argument, at, depth + 1))
                        "if" -> {
                            val parts = argument.array(at)
                            if (parts.size != 3) throw ScriptFormatException(at, "if requires condition, then and else")
                            ScriptExpression.Conditional(
                                condition = expression(parts[0], "$at[0]", depth + 1),
                                whenTrue = expression(parts[1], "$at[1]", depth + 1),
                                whenFalse = expression(parts[2], "$at[2]", depth + 1),
                            )
                        }
                        "map" -> {
                            val mapping = argument.obj("$at.value")
                            val table = mapping.values["table"]?.obj("$at.table")?.values
                                ?.mapValues { (key, value) -> value.string("$at.table.$key") }
                                ?: throw ScriptFormatException("$at.table", "map requires a table")
                            ScriptExpression.Mapping(
                                value = expression(
                                    mapping.values["value"] ?: throw ScriptFormatException("$at.value", "map requires a value"),
                                    "$at.value",
                                    depth + 1,
                                ),
                                table = table,
                            )
                        }
                        else -> throw ScriptFormatException(at, "unsupported expression '$op'")
                    }
                }
                else -> throw ScriptFormatException(at, "unsupported expression")
            }
        }

        fun steps(input: JsonValue, at: String, depth: Int): List<ScriptStep> {
            if (depth > MAX_DEPTH) throw ScriptFormatException(at, "step nesting exceeds $MAX_DEPTH")
            return input.array(at).mapIndexed { index, item ->
                visit(at)
                val stepPath = "$at[$index]"
                val fields = item.obj(stepPath)
                val op = fields.requiredString("op", stepPath)
                fun required(name: String) = fields.requiredString(name, stepPath)
                fun value() = expression(fields.required("value", stepPath), "$stepPath.value", 0)
                fun characteristic() = UUID.fromString(required("characteristic")).toString()
                fun service() = UUID.fromString(required("service")).toString()
                fun into() = fields.values["into"]?.string("$stepPath.into")
                fun timeout() = fields.values["timeoutMs"]?.integer("$stepPath.timeoutMs")
                    ?.also {
                        if (it !in 1..MAX_TIMEOUT_MS) {
                            throw ScriptFormatException("$stepPath.timeoutMs", "must be in 1..$MAX_TIMEOUT_MS")
                        }
                    }
                    ?.milliseconds
                fun matcher(): PacketMatcherSpec =
                    parseMatcher(fields.required("matcher", stepPath).obj("$stepPath.matcher"), "$stepPath.matcher")
                fun rejectUnknown(vararg allowed: String) {
                    fields.values.keys.firstOrNull { it !in allowed }?.let { unknown ->
                        throw ScriptFormatException("$stepPath.$unknown", "unknown field for '$op'")
                    }
                }
                try {
                    when (op) {
                        "ble.read" -> {
                            rejectUnknown("op", "service", "characteristic", "into")
                            ScriptStep.Read(service(), characteristic(), required("into"))
                        }
                        "ble.write" -> {
                            rejectUnknown("op", "service", "characteristic", "value", "withResponse")
                            ScriptStep.Write(service(), characteristic(), value(), fields.optionalBoolean("withResponse", stepPath) ?: true)
                        }
                        "ble.subscribe" -> {
                            rejectUnknown("op", "service", "characteristic", "into")
                            ScriptStep.Subscribe(service(), characteristic(), required("into"))
                        }
                        "state.get" -> {
                            rejectUnknown("op", "key", "into")
                            ScriptStep.GetState(required("key"), required("into"))
                        }
                        "state.set" -> {
                            rejectUnknown("op", "key", "value")
                            ScriptStep.SetState(required("key"), value())
                        }
                        "event.emit" -> {
                            rejectUnknown("op", "name", "value")
                            ScriptStep.Emit(required("name"), value())
                        }
                        "let" -> {
                            rejectUnknown("op", "name", "value")
                            ScriptStep.Assign(required("name"), value())
                        }
                        "if" -> {
                            rejectUnknown("op", "condition", "then", "else")
                            ScriptStep.If(
                                expression(fields.required("condition", stepPath), "$stepPath.condition", 0),
                                steps(fields.required("then", stepPath), "$stepPath.then", depth + 1),
                                fields.values["else"]?.let { steps(it, "$stepPath.else", depth + 1) } ?: emptyList(),
                            )
                        }
                        "repeat" -> {
                            rejectUnknown("op", "count", "steps")
                            ScriptStep.Repeat(
                                fields.required("count", stepPath).integer("$stepPath.count").also {
                                    if (it !in 0..32) throw ScriptFormatException("$stepPath.count", "must be 0..32")
                                },
                                steps(fields.required("steps", stepPath), "$stepPath.steps", depth + 1),
                            )
                        }
                        "return" -> {
                            rejectUnknown("op", "value")
                            ScriptStep.Return(value())
                        }
                        "session.send" -> {
                            rejectUnknown("op", "bytes")
                            val raw = fields.required("bytes", stepPath)
                            val expression = if (raw is JsonValue.StringValue) {
                                ScriptExpression.Literal(StateValue.BytesValue(hex(raw.value, "$stepPath.bytes")))
                            } else {
                                expression(raw, "$stepPath.bytes", 0)
                            }
                            ScriptStep.SessionSend(expression)
                        }
                        "session.request" -> {
                            rejectUnknown("op", "request", "transaction", "matcher", "timeoutMs", "into")
                            if ("request" in fields.values && "transaction" in fields.values) {
                                throw ScriptFormatException(
                                    "$stepPath.transaction",
                                    "cannot be combined with 'request'",
                                )
                            }
                            val endpoint = fields.values["request"]?.string("$stepPath.request")
                                ?: fields.values["transaction"]?.string("$stepPath.transaction")
                                ?: throw ScriptFormatException("$stepPath.request", "required")
                            ScriptStep.SessionRequest(
                                endpoint = endpoint,
                                matcher = if ("matcher" in fields.values) matcher() else null,
                                timeout = timeout(),
                                into = into(),
                            )
                        }
                        "session.waitFor" -> {
                            rejectUnknown("op", "matcher", "timeoutMs", "into")
                            ScriptStep.SessionWaitFor(
                                matcher = matcher(),
                                timeout = requireNotNull(timeout()) {
                                    "session.waitFor requires timeoutMs"
                                },
                                into = into(),
                            )
                        }
                        "session.onFrame" -> {
                            rejectUnknown("op", "matcher", "program")
                            ScriptStep.SessionOnFrame(
                                matcher = matcher(),
                                program = ScriptProgram(
                                    steps(fields.required("program", stepPath), "$stepPath.program", depth + 1),
                                ),
                            )
                        }
                        "extension.execute" -> {
                            rejectUnknown("op", "extension", "entry", "input", "timeoutMs", "into")
                            ScriptStep.ExtensionExecute(
                                extensionId = required("extension"),
                                entry = required("entry"),
                                input = expression(fields.required("input", stepPath), "$stepPath.input", 0),
                                timeout = requireNotNull(timeout()) {
                                    "extension.execute requires timeoutMs"
                                },
                                into = into(),
                            )
                        }
                        else -> throw ScriptFormatException("$stepPath.op", "unsupported operation '$op'")
                    }
                } catch (error: IllegalArgumentException) {
                    if (error is ScriptFormatException) throw error
                    throw ScriptFormatException(stepPath, error.message ?: "invalid step")
                }
            }
        }

        private fun parseMatcher(obj: JsonValue.Object, path: String): PacketMatcherSpec {
            val known = setOf("command", "commands", "sequence", "payloadPrefix", "payloadType", "payloadTypes")
            obj.values.keys.firstOrNull { it !in known }?.let { unknown ->
                throw ScriptFormatException("$path.$unknown", "unknown matcher field")
            }
            var matchRequestSequence = false
            var literalSequence: Int? = null
            when (val sequence = obj.values["sequence"]) {
                null -> Unit
                is JsonValue.BooleanValue -> matchRequestSequence = sequence.value
                is JsonValue.NumberValue -> literalSequence = sequence.raw.toIntOrNull()
                    ?: throw ScriptFormatException("$path.sequence", "must be an integer or boolean")
                is JsonValue.StringValue -> when (sequence.value.trim().lowercase()) {
                    "request" -> matchRequestSequence = true
                    else -> throw ScriptFormatException("$path.sequence", "must be 'request', a boolean or an integer")
                }
                else -> throw ScriptFormatException("$path.sequence", "must be 'request', a boolean or an integer")
            }
            val command = obj.values["command"]?.integer("$path.command")
            if (command != null && command !in 0..MAX_COMMAND) {
                throw ScriptFormatException("$path.command", "must fit UInt24")
            }
            val commands = obj.values["commands"]?.array("$path.commands")?.mapIndexed { index, item ->
                item.integer("$path.commands[$index]")
            }?.toSet() ?: emptySet()
            if (commands.any { it !in 0..MAX_COMMAND }) {
                throw ScriptFormatException("$path.commands", "every command must fit UInt24")
            }
            if (literalSequence != null && literalSequence !in 0..0xff) {
                throw ScriptFormatException("$path.sequence", "must fit UInt8")
            }
            val payloadTypes = buildSet {
                obj.values["payloadType"]?.integer("$path.payloadType")?.let(::add)
                obj.values["payloadTypes"]?.array("$path.payloadTypes")?.forEachIndexed { index, item ->
                    add(item.integer("$path.payloadTypes[$index]"))
                }
            }
            if (payloadTypes.any { it !in 0..0xff }) {
                throw ScriptFormatException("$path.payloadTypes", "every payload type must fit UInt8")
            }
            return PacketMatcherSpec(
                command = command,
                commands = commands,
                matchRequestSequence = matchRequestSequence,
                sequence = literalSequence,
                payloadPrefix = obj.values["payloadPrefix"]?.string("$path.payloadPrefix")
                    ?.let { hex(it, "$path.payloadPrefix") }
                    ?: ByteArray(0),
                payloadTypes = payloadTypes,
            )
        }
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

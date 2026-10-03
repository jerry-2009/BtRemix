package com.Fusion.Btremix.scripting.api

import com.Fusion.Btremix.device.runtime.StateValue

/** Raised when an expression cannot be evaluated against the values supplied by the caller. */
class ScriptEvaluationException(message: String) : IllegalArgumentException(message)

/**
 * Evaluates one allowlisted expression against [args] and [variables].
 *
 * This is shared by the full script driver and by declarative bindings (notification decoding).
 * It never touches Android or reflection; [getState] is the only external capability, so callers
 * that do not allow state reads can pass `{ null }`.
 */
suspend fun evaluateScriptExpression(
    expression: ScriptExpression,
    args: Map<String, StateValue>,
    variables: Map<String, StateValue>,
    maxBytes: Int = 4096,
    getState: suspend (String) -> StateValue? = { null },
): StateValue = when (expression) {
    is ScriptExpression.Literal -> expression.value
    is ScriptExpression.Argument -> args[expression.name]
        ?: throw ScriptEvaluationException("missing argument '${expression.name}'")
    is ScriptExpression.Variable -> variables[expression.name]
        ?: throw ScriptEvaluationException("missing variable '${expression.name}'")
    is ScriptExpression.State -> getState(expression.key)
        ?: throw ScriptEvaluationException("missing state '${expression.key}'")
    is ScriptExpression.ByteAt -> {
        val bytes = (evaluateScriptExpression(expression.value, args, variables, maxBytes, getState) as? StateValue.BytesValue)?.value
            ?: throw ScriptEvaluationException("at requires bytes")
        if (expression.index >= bytes.size) throw ScriptEvaluationException("byte index out of bounds")
        StateValue.IntValue(bytes[expression.index].toInt() and 0xff)
    }
    is ScriptExpression.Equals -> StateValue.BooleanValue(
        evaluateScriptExpression(expression.left, args, variables, maxBytes, getState) ==
            evaluateScriptExpression(expression.right, args, variables, maxBytes, getState),
    )
    is ScriptExpression.Concat -> {
        val parts = expression.values.map { part ->
            (evaluateScriptExpression(part, args, variables, maxBytes, getState) as? StateValue.BytesValue)?.value
                ?: throw ScriptEvaluationException("concat requires bytes")
        }
        val bytes = parts.fold(ByteArray(0)) { acc, item -> acc + item }
        if (bytes.size > maxBytes) throw ScriptEvaluationException("byte limit exceeded")
        StateValue.BytesValue(bytes)
    }
    is ScriptExpression.Conditional -> {
        val condition = evaluateScriptExpression(expression.condition, args, variables, maxBytes, getState)
        val selected = when (condition) {
            is StateValue.BooleanValue -> if (condition.value) expression.whenTrue else expression.whenFalse
            else -> throw ScriptEvaluationException("if requires a boolean condition")
        }
        evaluateScriptExpression(selected, args, variables, maxBytes, getState)
    }
    is ScriptExpression.Mapping -> {
        val value = evaluateScriptExpression(expression.value, args, variables, maxBytes, getState)
        val key = when (value) {
            is StateValue.IntValue -> value.value.toString()
            is StateValue.LongValue -> value.value.toString()
            is StateValue.StringValue -> value.value
            is StateValue.DoubleValue -> value.value.toString()
            else -> throw ScriptEvaluationException("map requires a number or a string")
        }
        StateValue.StringValue(expression.table[key] ?: key)
    }
    is ScriptExpression.Length -> {
        val bytes = (evaluateScriptExpression(expression.value, args, variables, maxBytes, getState) as? StateValue.BytesValue)?.value
            ?: throw ScriptEvaluationException("len requires bytes")
        StateValue.IntValue(bytes.size)
    }
}

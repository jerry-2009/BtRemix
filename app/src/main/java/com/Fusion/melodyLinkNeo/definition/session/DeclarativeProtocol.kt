package com.fusion.melodyLinkNeo.definition.session

import com.fusion.melodyLinkNeo.definition.api.ActionDefinition
import com.fusion.melodyLinkNeo.definition.api.DefinitionProtocolFactory
import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.definition.api.MessageDefinition
import com.fusion.melodyLinkNeo.definition.api.NotifyDefinition
import com.fusion.melodyLinkNeo.definition.api.ProtocolFieldDefinition
import com.fusion.melodyLinkNeo.definition.api.ProtocolFieldType
import com.fusion.melodyLinkNeo.definition.api.StateDefinition
import com.fusion.melodyLinkNeo.device.runtime.DeviceAction
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import com.fusion.melodyLinkNeo.protocol.api.EnumValue
import com.fusion.melodyLinkNeo.protocol.api.Field
import com.fusion.melodyLinkNeo.protocol.api.Message
import com.fusion.melodyLinkNeo.protocol.api.MessageSchema
import com.fusion.melodyLinkNeo.scripting.api.ScriptEvaluationException
import com.fusion.melodyLinkNeo.scripting.api.evaluateScriptExpression

/**
 * Shared declarative-protocol plumbing used by both the GATT ([DefinitionBindings]) and the
 * byte-stream ([StreamDefinitionBindings]) binding paths.
 *
 * It owns exactly the transport-independent parts: turning action arguments into a request message,
 * turning a response or a notification payload into a state value, and evaluating the optional
 * notification `if` guard.
 */
internal class DeclarativeProtocolSupport(
    private val definition: LoadedDeviceDefinition,
    private val getState: suspend (String) -> StateValue?,
) {
    val factory: DefinitionProtocolFactory = definition.protocolFactory()

    fun schema(name: String): MessageSchema = factory.schema(name)

    suspend fun buildMessage(action: ActionDefinition, messageName: String, input: DeviceAction): Message {
        val schema = schema(messageName)
        val messageDefinition: MessageDefinition = definition.protocol.messages[messageName]
            ?: throw IllegalArgumentException("Unknown message '$messageName'")
        val values = messageDefinition.fields.associate { field ->
            val expression = action.arguments[field.name]
                ?: throw IllegalArgumentException("Action '${action.id}' has no value for field '${field.name}'")
            val value = evaluateScriptExpression(
                expression = expression,
                args = input.args,
                variables = emptyMap(),
                getState = getState,
            )
            field.name to toFieldValue(field, value, action.id)
        }
        val builder = Message.builder()
        schema.fields.forEach { field ->
            @Suppress("UNCHECKED_CAST")
            builder.set(field as Field<Any>, values.getValue(field.name))
        }
        return builder.build()
    }

    suspend fun resultValue(action: ActionDefinition, input: DeviceAction): StateValue? {
        if (action.resultState == null) return null
        val expression = action.result
            ?: action.parameters.firstOrNull()?.let { com.fusion.melodyLinkNeo.scripting.api.ScriptExpression.Argument(it.name) }
            ?: return null
        return evaluateScriptExpression(
            expression = expression,
            args = input.args,
            variables = emptyMap(),
            getState = getState,
        )
    }

    /**
     * Decodes a notification payload.
     *
     * A `false` guard means the update must be skipped, and so does a payload the declaration cannot
     * evaluate against (for example a shorter frame that happens to share the payload type): such a
     * frame must never tear down the continuous subscription that keeps the state in sync.
     */
    suspend fun decodeNotification(state: StateDefinition, notify: NotifyDefinition, bytes: ByteArray): StateValue? = try {
        decodeNotificationOrThrow(state, notify, bytes)
    } catch (_: ScriptEvaluationException) {
        null
    }

    private suspend fun decodeNotificationOrThrow(
        state: StateDefinition,
        notify: NotifyDefinition,
        bytes: ByteArray,
    ): StateValue? {
        val condition = notify.condition
        if (condition != null) {
            val value = evaluateScriptExpression(
                expression = condition,
                args = emptyMap(),
                variables = mapOf(RAW_VARIABLE to StateValue.BytesValue(bytes.clone())),
                getState = getState,
            )
            val matches = (value as? StateValue.BooleanValue)?.value
                ?: throw IllegalArgumentException("Notification guard for '${state.key}' must be a boolean")
            if (!matches) return null
        }
        val decode = notify.decode ?: return StateValue.BytesValue(bytes.clone())
        return evaluateScriptExpression(
            expression = decode,
            args = emptyMap(),
            variables = mapOf(RAW_VARIABLE to StateValue.BytesValue(bytes.clone())),
            getState = getState,
        )
    }

    private fun toFieldValue(field: ProtocolFieldDefinition, value: StateValue, actionId: String): Any = when (field.type) {
        ProtocolFieldType.UINT8,
        ProtocolFieldType.INT8,
        ProtocolFieldType.UINT16,
        ProtocolFieldType.INT16,
        ProtocolFieldType.INT32,
        ProtocolFieldType.BIT_FIELD,
        -> integer(value)
        ProtocolFieldType.UINT32 -> when (value) {
            is StateValue.IntValue -> value.value.toLong()
            is StateValue.LongValue -> value.value
            is StateValue.DoubleValue -> value.value.toLong()
            is StateValue.FloatValue -> value.value.toLong()
            else -> throw IllegalArgumentException("Action '$actionId' field '${field.name}' requires an integer")
        }
        ProtocolFieldType.FLOAT32 -> when (value) {
            is StateValue.IntValue -> value.value.toFloat()
            is StateValue.LongValue -> value.value.toFloat()
            is StateValue.FloatValue -> value.value
            is StateValue.DoubleValue -> value.value.toFloat()
            else -> throw IllegalArgumentException("Action '$actionId' field '${field.name}' requires a number")
        }
        ProtocolFieldType.BYTES,
        ProtocolFieldType.SLICE,
        ProtocolFieldType.CONCAT,
        -> (value as? StateValue.BytesValue)?.value
            ?: throw IllegalArgumentException("Action '$actionId' field '${field.name}' requires bytes")
        ProtocolFieldType.STRING -> (value as? StateValue.StringValue)?.value
            ?: throw IllegalArgumentException("Action '$actionId' field '${field.name}' requires a string")
        ProtocolFieldType.ENUM -> when (value) {
            is StateValue.StringValue -> enumValue(field, value.value, actionId)
            is StateValue.IntValue -> EnumValue(value.value, field.enumValues[value.value])
            else -> throw IllegalArgumentException("Action '$actionId' field '${field.name}' requires an enum value")
        }
    }

    private fun enumValue(field: ProtocolFieldDefinition, name: String, actionId: String): EnumValue {
        val raw = field.enumValues.entries.firstOrNull { it.value == name }?.key
            ?: name.toIntOrNull()
            ?: throw IllegalArgumentException(
                "Action '$actionId' field '${field.name}' value '$name' is not one of ${field.enumValues.values}",
            )
        return EnumValue(raw, field.enumValues[raw])
    }

    private fun integer(value: StateValue): Int = when (value) {
        is StateValue.IntValue -> value.value
        is StateValue.LongValue -> value.value.toInt()
        is StateValue.BooleanValue -> if (value.value) 1 else 0
        is StateValue.DoubleValue -> value.value.toInt()
        is StateValue.FloatValue -> value.value.toInt()
        is StateValue.StringValue -> value.value.toIntOrNull()
            ?: throw IllegalArgumentException("'${value.value}' is not an integer")
        else -> throw IllegalArgumentException("Value is not an integer")
    }

    companion object {
        const val RAW_VARIABLE = "raw"
    }
}

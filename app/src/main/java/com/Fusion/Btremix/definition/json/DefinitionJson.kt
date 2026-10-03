package com.Fusion.Btremix.definition.json

import com.Fusion.Btremix.definition.api.DefinitionManifest
import com.Fusion.Btremix.definition.api.DeviceMatchRule
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.MessageDefinition
import com.Fusion.Btremix.definition.api.PacketDefinition
import com.Fusion.Btremix.definition.api.ProtocolDefinition
import com.Fusion.Btremix.definition.api.ProtocolFieldDefinition
import com.Fusion.Btremix.definition.api.ProtocolFieldType
import com.Fusion.Btremix.definition.api.TransactionDefinition
import com.Fusion.Btremix.definition.api.ActionDefinition
import com.Fusion.Btremix.definition.api.ActionParameterDefinition
import com.Fusion.Btremix.definition.api.DefinitionSchema
import com.Fusion.Btremix.definition.api.NotifyDefinition
import com.Fusion.Btremix.definition.api.StateDefinition
import com.Fusion.Btremix.definition.api.StateDefinitionType
import com.Fusion.Btremix.definition.api.TransportDefinition
import com.Fusion.Btremix.definition.api.UiNode
import com.Fusion.Btremix.definition.api.UiSchema
import com.Fusion.Btremix.scripting.api.ScriptCodec
import com.Fusion.Btremix.scripting.api.ScriptExpression
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.definition.validator.DefinitionValidator
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class DefinitionJsonException(val path: String, message: String) : IllegalArgumentException("$path: $message")

object DefinitionJsonCodec {
    fun decode(text: String, validate: Boolean = true): LoadedDeviceDefinition {
        val root = JsonParser.parse(text).obj("$")
        val manifestObject = root.optionalObj("manifest") ?: root
        val protocolObject = root.optionalObj("protocol")
        val definition = LoadedDeviceDefinition(
            manifest = parseManifest(manifestObject),
            protocol = protocolObject?.let(::parseProtocol) ?: ProtocolDefinition(),
            states = root.values["states"]?.let { parseStates(it) } ?: emptyMap(),
            actions = root.values["actions"]?.let { parseActions(it) } ?: emptyMap(),
            ui = root.optionalObj("ui")?.let(::parseUi) ?: UiSchema(),
        )
        return if (validate) DefinitionValidator.requireValid(definition) else definition
    }

    private fun parseManifest(obj: JsonValue.Object): DefinitionManifest = DefinitionManifest(
        id = obj.requiredString("id"),
        displayName = obj.requiredString("displayName"),
        version = obj.requiredString("version"),
        schemaVersion = obj.optionalInt("schemaVersion") ?: DefinitionSchema.VERSION_SCRIPT_ONLY,
        runtime = obj.optionalString("runtime"),
        capabilities = obj.optionalArray("capabilities")?.values?.mapIndexed { index, value -> value.string("manifest.capabilities[$index]") }?.toSet() ?: emptySet(),
        matchers = obj.optionalArray("matchers")?.values?.mapIndexed { index, value -> parseMatcher(value.obj("manifest.matchers[$index]"), index) } ?: emptyList(),
    )

    private fun parseMatcher(obj: JsonValue.Object, index: Int): DeviceMatchRule {
        val path = "manifest.matchers[$index]"
        val type = obj.requiredString("type")
        val priority = obj.optionalInt("priority") ?: when (type) {
            "nameExact" -> 100
            "namePrefix" -> 50
            "nameRegex" -> 25
            "addressRegex" -> 150
            "serviceUuid" -> 200
            "manufacturerData" -> 300
            else -> 0
        }
        return when (type) {
            "nameExact" -> DeviceMatchRule.NameExact(obj.requiredString("value"), priority)
            "namePrefix" -> DeviceMatchRule.NamePrefix(obj.requiredString("value"), priority)
            "nameRegex" -> runCatching { DeviceMatchRule.NameRegex(obj.requiredString("value"), priority) }
                .getOrElse { throw DefinitionJsonException("$path.value", "invalid regular expression") }
            "addressRegex" -> runCatching { DeviceMatchRule.AddressRegex(obj.requiredString("value"), priority) }
                .getOrElse { throw DefinitionJsonException("$path.value", "invalid regular expression") }
            "serviceUuid" -> DeviceMatchRule.ServiceUuid(parseUuid(obj.requiredString("value"), "$path.value"), priority)
            "manufacturerData" -> DeviceMatchRule.ManufacturerData(
                companyId = obj.requiredInt("companyId"),
                prefix = parseBytes(obj.optionalString("prefix") ?: "", "$path.prefix"),
                priority = priority,
            )
            else -> throw DefinitionJsonException("$path.type", "unknown matcher type")
        }
    }

    private fun parseProtocol(obj: JsonValue.Object): ProtocolDefinition {
        val messages = obj.optionalObj("messages")?.values?.mapValues { (name, value) -> parseMessage(name, value.obj("protocol.messages.$name")) } ?: emptyMap()
        val transactions = obj.optionalObj("transactions")?.values?.mapValues { (name, value) -> parseTransaction(name, value.obj("protocol.transactions.$name")) } ?: emptyMap()
        val packet = obj.optionalObj("packet")?.let { PacketDefinition(it.optionalBoolean("includesSequence") ?: true) } ?: PacketDefinition()
        return ProtocolDefinition(
            endianness = when ((obj.optionalString("endianness") ?: "little").lowercase()) {
                "little", "little_endian", "le" -> com.Fusion.Btremix.protocol.api.Endianness.LITTLE
                "big", "big_endian", "be" -> com.Fusion.Btremix.protocol.api.Endianness.BIG
                else -> throw DefinitionJsonException("protocol.endianness", "must be 'little' or 'big'")
            },
            packet = packet,
            transport = obj.optionalObj("transport")?.let(::parseTransport),
            framing = obj.optionalObj("framing")?.let(::parseFraming),
            messages = messages,
            transactions = transactions,
            initialize = obj.optionalArray("initialize")?.values?.mapIndexed { index, value ->
                value.string("protocol.initialize[$index]")
            } ?: emptyList(),
        )
    }

    private fun parseTransport(obj: JsonValue.Object): TransportDefinition = TransportDefinition(
        service = obj.requiredString("service"),
        characteristic = obj.optionalString("characteristic"),
        withResponse = obj.optionalBoolean("withResponse") ?: true,
        type = when (val type = (obj.optionalString("type") ?: "gatt").lowercase()) {
            "gatt" -> com.Fusion.Btremix.definition.api.TransportType.GATT
            "rfcomm", "spp", "classic" -> com.Fusion.Btremix.definition.api.TransportType.RFCOMM
            else -> throw DefinitionJsonException("protocol.transport.type", "must be 'gatt' or 'rfcomm', not '$type'")
        },
        bonded = obj.optionalBoolean("bonded") ?: true,
    )

    private fun parseFraming(obj: JsonValue.Object): com.Fusion.Btremix.definition.api.FramingDefinition =
        com.Fusion.Btremix.definition.api.FramingDefinition(
            codec = obj.requiredString("codec"),
            header = obj.optionalInt("header"),
            trailer = obj.optionalInt("trailer"),
            escape = obj.optionalInt("escape"),
            escapeMask = obj.optionalInt("escapeMask") ?: 0xEF,
            checksum = obj.optionalString("checksum"),
            messageTypeOffset = obj.optionalObj("layout")?.optionalInt("messageTypeOffset") ?: 0,
            sequenceOffset = obj.optionalObj("layout")?.let { layout -> layout.optionalInt("sequenceOffset") } ?: 1,
            lengthOffset = obj.optionalObj("layout")?.optionalInt("lengthOffset") ?: 2,
            lengthBytes = obj.optionalObj("layout")?.optionalInt("lengthBytes") ?: 4,
            lengthByteOrder = obj.optionalObj("layout")?.optionalString("lengthByteOrder")
                ?.let { parseEndianness(it, "protocol.framing.layout.lengthByteOrder") }
                ?: com.Fusion.Btremix.protocol.api.Endianness.BIG,
            acknowledgeMessageTypes = obj.optionalObj("acknowledge")?.optionalArray("messageTypes")
                ?.values?.mapIndexed { index, value -> value.number("protocol.framing.acknowledge.messageTypes[$index]").raw.toIntOrNull() ?: throw DefinitionJsonException("protocol.framing.acknowledge.messageTypes[$index]", "must be an integer") }
                ?: emptyList(),
            acknowledgeReplyMessageType = obj.optionalObj("acknowledge")?.optionalInt("replyMessageType"),
            acknowledgeSequence = obj.optionalObj("acknowledge")?.optionalString("sequence") ?: "complement",
        )

    private fun parseState(key: String, obj: JsonValue.Object): StateDefinition = StateDefinition(
        key = key,
        type = parseDefinitionType(obj.requiredString("type"), "states.$key.type"),
        defaultValue = obj.values["default"]?.let { parseStateValue(it, "states.$key.default") },
        displayName = obj.optionalString("displayName") ?: obj.optionalString("label"),
        description = obj.optionalString("description"),
        unit = obj.optionalString("unit"),
        enumValues = parseStringMap(obj.optionalObj("enumValues"), "states.$key.enumValues"),
        min = obj.optionalDouble("min"), max = obj.optionalDouble("max"), step = obj.optionalDouble("step"),
        notify = obj.optionalObj("notify")?.let { parseNotify(key, it) },
    )

    private fun parseNotify(stateKey: String, obj: JsonValue.Object): NotifyDefinition = NotifyDefinition(
        service = obj.optionalString("service"),
        characteristic = obj.optionalString("characteristic"),
        decode = obj.values["decode"]?.let { ScriptCodec.parseExpression(it, "states.$stateKey.notify.decode") },
        payloadTypes = buildSet {
            obj.optionalInt("payloadType")?.let(::add)
            obj.optionalArray("payloadTypes")?.values?.forEachIndexed { index, value ->
                add(value.number("states.$stateKey.notify.payloadTypes[$index]").raw.toIntOrNull()
                    ?: throw DefinitionJsonException("states.$stateKey.notify.payloadTypes[$index]", "must be an integer"))
            }
        },
        condition = obj.values["if"]?.let { ScriptCodec.parseExpression(it, "states.$stateKey.notify.if") },
    )

    private fun parseStates(value: JsonValue): Map<String, StateDefinition> = when (value) {
        is JsonValue.Object -> value.values.mapValues { (key, item) -> parseState(key, item.obj("states.$key")) }
        is JsonValue.Array -> value.values.mapIndexed { index, item ->
            val obj = item.obj("states[$index]")
            val key = obj.optionalString("key") ?: obj.optionalString("id") ?: throw DefinitionJsonException("states[$index]", "requires key")
            key to parseState(key, obj)
        }.toMap()
        else -> throw DefinitionJsonException("states", "must be an object or array")
    }

    private fun parseAction(id: String, obj: JsonValue.Object): ActionDefinition = ActionDefinition(
        id = id,
        displayName = obj.optionalString("displayName") ?: obj.optionalString("label") ?: id,
        description = obj.optionalString("description"),
        parameters = obj.optionalArray("parameters")?.values?.mapIndexed { index, value ->
            val parameter = value.obj("actions.$id.parameters[$index]")
            ActionParameterDefinition(
                name = parameter.requiredString("name"),
                type = parseDefinitionType(parameter.requiredString("type"), "actions.$id.parameters[$index].type"),
                required = parameter.optionalBoolean("required") ?: true,
                displayName = parameter.optionalString("displayName") ?: parameter.optionalString("label"),
                enumValues = parseStringMap(parameter.optionalObj("enumValues"), "actions.$id.parameters[$index].enumValues"),
                min = parameter.optionalDouble("min"), max = parameter.optionalDouble("max"), step = parameter.optionalDouble("step"),
            )
        } ?: emptyList(),
        resultState = obj.optionalString("resultState"),
        script = obj.values["script"]?.let { ScriptCodec.parse(it, "actions.$id.script") },
        transaction = obj.optionalString("transaction"),
        arguments = obj.optionalObj("arguments")?.values?.mapValues { (field, value) ->
            ScriptCodec.parseExpression(value, "actions.$id.arguments.$field")
        } ?: emptyMap(),
        result = obj.values["result"]?.let { ScriptCodec.parseExpression(it, "actions.$id.result") },
        refresh = parseTransactionNames(obj.values["refresh"], "actions.$id.refresh"),
    )

    /** `refresh` accepts one transaction name or an ordered list of names. */
    private fun parseTransactionNames(value: JsonValue?, path: String): List<String> = when (value) {
        null -> emptyList()
        is JsonValue.StringValue -> listOf(value.value)
        is JsonValue.Array -> value.values.mapIndexed { index, item -> item.string("$path[$index]") }
        else -> throw DefinitionJsonException(path, "must be a string or an array of strings")
    }

    private fun parseActions(value: JsonValue): Map<String, ActionDefinition> = when (value) {
        is JsonValue.Object -> value.values.mapValues { (id, item) -> parseAction(id, item.obj("actions.$id")) }
        is JsonValue.Array -> value.values.mapIndexed { index, item ->
            val obj = item.obj("actions[$index]")
            val id = obj.optionalString("id") ?: throw DefinitionJsonException("actions[$index]", "requires id")
            id to parseAction(id, obj)
        }.toMap()
        else -> throw DefinitionJsonException("actions", "must be an object or array")
    }

    private fun parseUi(obj: JsonValue.Object): UiSchema = UiSchema(
        title = obj.optionalString("title"),
        children = obj.optionalArray("children")?.values?.mapIndexed { index, value -> parseUiNode(value.obj("ui.children[$index]"), "ui.children[$index]") } ?: emptyList(),
    )

    private fun parseUiNode(obj: JsonValue.Object, path: String): UiNode {
        val type = obj.requiredString("type").lowercase()
        val id = obj.optionalString("id")
        fun state() = obj.requiredString("state")
        fun action() = obj.requiredString("action")
        fun children() = obj.optionalArray("children")?.values?.mapIndexed { index, value -> parseUiNode(value.obj("$path.children[$index]"), "$path.children[$index]") } ?: emptyList()
        return when (type) {
            "column", "layout" -> UiNode.Column(id, children())
            "section" -> UiNode.Section(obj.requiredString("title"), id, children())
            "text" -> UiNode.Text(obj.optionalString("text"), obj.optionalString("state"), id)
            "value" -> UiNode.Value(state(), id)
            "switch" -> UiNode.Switch(state(), action(), id)
            "slider" -> UiNode.Slider(state(), action(), id)
            "button" -> UiNode.Button(obj.optionalString("label") ?: obj.optionalString("text") ?: action(), action(), parseArgs(obj.values["args"], "$path.args"), id)
            "segmented" -> UiNode.Segmented(state(), action(), obj.requiredArray("options").values.mapIndexed { index, value -> value.string("$path.options[$index]") }, id)
            "progress" -> UiNode.Progress(state(), id)
            else -> throw DefinitionJsonException("$path.type", "unknown UI node type")
        }
    }

    private fun parseArgs(value: JsonValue?, path: String): Map<String, StateValue> {
        if (value == null) return emptyMap()
        return value.obj(path).values.mapValues { (key, item) -> parseStateValue(item, "$path.$key") }
    }

    private fun parseDefinitionType(value: String, path: String): StateDefinitionType = when (value.lowercase().replace("_", "").replace("-", "")) {
        "boolean", "bool" -> StateDefinitionType.BOOLEAN
        "integer", "int", "long" -> StateDefinitionType.INTEGER
        "number", "float", "double" -> StateDefinitionType.NUMBER
        "string" -> StateDefinitionType.STRING
        "enum" -> StateDefinitionType.ENUM
        "bytes", "bytearray" -> StateDefinitionType.BYTES
        else -> throw DefinitionJsonException(path, "unknown definition type")
    }

    private fun parseStateValue(value: JsonValue, path: String): StateValue = when (value) {
        is JsonValue.BooleanValue -> StateValue.BooleanValue(value.value)
        is JsonValue.StringValue -> StateValue.StringValue(value.value)
        is JsonValue.NumberValue -> value.raw.toIntOrNull()?.let(StateValue::IntValue) ?: value.raw.toDoubleOrNull()?.let(StateValue::DoubleValue) ?: throw DefinitionJsonException(path, "invalid number")
        is JsonValue.Array -> StateValue.ListValue(value.values.mapIndexed { index, item -> parseStateValue(item, "$path[$index]") })
        else -> throw DefinitionJsonException(path, "must be a scalar or array value")
    }

    private fun parseStringMap(obj: JsonValue.Object?, path: String): Map<String, String> = obj?.values?.mapValues { (key, value) -> value.string("$path.$key") } ?: emptyMap()

    private fun parseMessage(name: String, obj: JsonValue.Object): MessageDefinition {
        val fields = obj.requiredArray("fields").values.mapIndexed { index, value -> parseField(value.obj("protocol.messages.$name.fields[$index]"), "protocol.messages.$name.fields[$index]") }
        val endianness = obj.optionalString("endianness")?.let { parseEndianness(it, "protocol.messages.$name.endianness") }
        return MessageDefinition(name, fields, endianness)
    }

    private fun parseField(obj: JsonValue.Object, path: String): ProtocolFieldDefinition {
        val typeName = obj.requiredString("type").lowercase().replace("_", "").replace("-", "")
        val type = when (typeName) {
            "uint8" -> ProtocolFieldType.UINT8
            "int8" -> ProtocolFieldType.INT8
            "uint16" -> ProtocolFieldType.UINT16
            "int16" -> ProtocolFieldType.INT16
            "uint32" -> ProtocolFieldType.UINT32
            "int32" -> ProtocolFieldType.INT32
            "float32", "float" -> ProtocolFieldType.FLOAT32
            "bytes" -> ProtocolFieldType.BYTES
            "string" -> ProtocolFieldType.STRING
            "enum" -> ProtocolFieldType.ENUM
            "bitfield" -> ProtocolFieldType.BIT_FIELD
            "slice" -> ProtocolFieldType.SLICE
            "concat" -> ProtocolFieldType.CONCAT
            else -> throw DefinitionJsonException("$path.type", "unknown field type")
        }
        val parts = obj.optionalArray("parts")?.values?.mapIndexed { index, value -> parseField(value.obj("$path.parts[$index]"), "$path.parts[$index]") } ?: emptyList()
        val enums = obj.optionalObj("enumValues")?.values?.mapKeys { (key, _) -> key.toIntOrNull() ?: throw DefinitionJsonException("$path.enumValues", "keys must be integers") }
            ?.mapValues { (key, value) -> value.string("$path.enumValues.$key") } ?: emptyMap()
        return ProtocolFieldDefinition(
            name = obj.requiredString("name"),
            type = type,
            length = obj.optionalInt("length"),
            offset = obj.optionalInt("offset") ?: 0,
            charset = obj.optionalString("charset") ?: "UTF-8",
            enumValues = enums,
            mask = obj.optionalInt("mask"),
            shift = obj.optionalInt("shift"),
            parts = parts,
        )
    }

    private fun parseTransaction(name: String, obj: JsonValue.Object): TransactionDefinition = TransactionDefinition(
        name = name,
        requestCommand = obj.requiredInt("requestCommand"),
        requestMessage = obj.optionalString("requestMessage"),
        expectedCommand = obj.optionalInt("expectedCommand"),
        responseMessage = obj.optionalString("responseMessage"),
        timeout = obj.optionalLong("timeoutMs")?.milliseconds,
        retries = obj.optionalInt("retries") ?: 0,
        expectedPayloadTypes = buildSet {
            obj.optionalInt("expectedPayloadType")?.let(::add)
            obj.optionalArray("expectedPayloadTypes")?.values?.forEachIndexed { index, value ->
                add(value.number("protocol.transactions.$name.expectedPayloadTypes[$index]").raw.toIntOrNull()
                    ?: throw DefinitionJsonException("protocol.transactions.$name.expectedPayloadTypes[$index]", "must be an integer"))
            }
        },
        expectsResponse = obj.optionalBoolean("expectsResponse") ?: true,
        requestPayload = obj.optionalString("requestPayload")?.let { parseBytes(it, "protocol.transactions.$name.requestPayload") },
    )

    private fun parseEndianness(value: String, path: String) = when (value.lowercase()) {
        "little", "little_endian", "le" -> com.Fusion.Btremix.protocol.api.Endianness.LITTLE
        "big", "big_endian", "be" -> com.Fusion.Btremix.protocol.api.Endianness.BIG
        else -> throw DefinitionJsonException(path, "must be 'little' or 'big'")
    }

    private fun parseUuid(value: String, path: String): UUID = runCatching { UUID.fromString(value) }
        .getOrElse { throw DefinitionJsonException(path, "must be a UUID") }

    private fun parseBytes(value: String, path: String): ByteArray {
        val normalized = value.replace(" ", "").replace(":", "")
        if (normalized.length % 2 != 0 || normalized.any { it.digitToIntOrNull(16) == null }) throw DefinitionJsonException(path, "must be an even-length hexadecimal string")
        return ByteArray(normalized.length / 2) { index -> normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}

private fun JsonValue.obj(path: String): JsonValue.Object = this as? JsonValue.Object ?: throw DefinitionJsonException(path, "must be an object")
private fun JsonValue.string(path: String): String = (this as? JsonValue.StringValue)?.value ?: throw DefinitionJsonException(path, "must be a string")
private fun JsonValue.number(path: String): JsonValue.NumberValue = this as? JsonValue.NumberValue ?: throw DefinitionJsonException(path, "must be a number")
private fun JsonValue.Object.required(name: String): JsonValue = values[name] ?: throw DefinitionJsonException("$name", "is required")
private fun JsonValue.Object.requiredString(name: String): String = required(name).string(name)
private fun JsonValue.Object.requiredArray(name: String): JsonValue.Array = required(name) as? JsonValue.Array ?: throw DefinitionJsonException(name, "must be an array")
private fun JsonValue.Object.optionalObj(name: String): JsonValue.Object? = values[name]?.obj(name)
private fun JsonValue.Object.optionalArray(name: String): JsonValue.Array? = values[name]?.let { it as? JsonValue.Array ?: throw DefinitionJsonException(name, "must be an array") }
private fun JsonValue.Object.optionalString(name: String): String? = values[name]?.string(name)
private fun JsonValue.Object.optionalBoolean(name: String): Boolean? = values[name]?.let { (it as? JsonValue.BooleanValue)?.value ?: throw DefinitionJsonException(name, "must be a boolean") }
private fun JsonValue.Object.optionalInt(name: String): Int? = values[name]?.number(name)?.raw?.toIntOrNull() ?: values[name]?.let { throw DefinitionJsonException(name, "must be an integer") }
private fun JsonValue.Object.requiredInt(name: String): Int = optionalInt(name) ?: throw DefinitionJsonException(name, "is required")
private fun JsonValue.Object.optionalLong(name: String): Long? = values[name]?.number(name)?.raw?.toLongOrNull() ?: values[name]?.let { throw DefinitionJsonException(name, "must be an integer") }
private fun JsonValue.Object.optionalDouble(name: String): Double? = values[name]?.number(name)?.raw?.toDoubleOrNull() ?: values[name]?.let { throw DefinitionJsonException(name, "must be a number") }

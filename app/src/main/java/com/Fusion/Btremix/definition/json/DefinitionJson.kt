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
        )
        return if (validate) DefinitionValidator.requireValid(definition) else definition
    }

    private fun parseManifest(obj: JsonValue.Object): DefinitionManifest = DefinitionManifest(
        id = obj.requiredString("id"),
        displayName = obj.requiredString("displayName"),
        version = obj.requiredString("version"),
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
            messages = messages,
            transactions = transactions,
        )
    }

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
        timeout = obj.optionalLong("timeoutMs")?.milliseconds,
        retries = obj.optionalInt("retries") ?: 0,
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

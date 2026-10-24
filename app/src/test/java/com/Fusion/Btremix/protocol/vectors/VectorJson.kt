package com.Fusion.Btremix.protocol.vectors

import com.Fusion.Btremix.definition.json.JsonParser
import com.Fusion.Btremix.definition.json.JsonValue

internal object VectorJson {
    fun resource(name: String): JsonValue.Object {
        val stream = requireNotNull(VectorJson::class.java.getResourceAsStream("/vectors/$name")) {
            "Missing test fixture /vectors/$name"
        }
        return JsonParser.parse(stream.bufferedReader().use { it.readText() }).requiredObject("$")
    }
}

internal fun JsonValue.requiredObject(path: String): JsonValue.Object =
    this as? JsonValue.Object ?: error("$path must be a JSON object")

internal fun JsonValue.requiredArray(path: String): JsonValue.Array =
    this as? JsonValue.Array ?: error("$path must be a JSON array")

internal fun JsonValue.requiredString(path: String): String =
    (this as? JsonValue.StringValue)?.value ?: error("$path must be a JSON string")

internal fun JsonValue.requiredInt(path: String): Int =
    (this as? JsonValue.NumberValue)?.raw?.toIntOrNull() ?: error("$path must be a JSON integer")

internal fun JsonValue.optionalInt(path: String): Int? = when (this) {
    JsonValue.NullValue -> null
    is JsonValue.NumberValue -> raw.toIntOrNull() ?: error("$path must be a JSON integer")
    else -> error("$path must be a JSON integer or null")
}

internal fun JsonValue.Object.requiredObject(name: String): JsonValue.Object =
    requireNotNull(values[name]) { "$name is required" }.requiredObject(name)

internal fun JsonValue.Object.requiredArray(name: String): JsonValue.Array =
    requireNotNull(values[name]) { "$name is required" }.requiredArray(name)

internal fun JsonValue.Object.requiredString(name: String): String =
    requireNotNull(values[name]) { "$name is required" }.requiredString(name)

internal fun JsonValue.Object.requiredInt(name: String): Int =
    requireNotNull(values[name]) { "$name is required" }.requiredInt(name)

internal fun JsonValue.Object.optionalInt(name: String): Int? =
    values[name]?.optionalInt(name)

internal fun hexBytes(text: String): ByteArray {
    val compact = text.filterNot(Char::isWhitespace)
    require(compact.length % 2 == 0) { "Hex must contain complete bytes: '$text'" }
    return ByteArray(compact.length / 2) { index ->
        compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

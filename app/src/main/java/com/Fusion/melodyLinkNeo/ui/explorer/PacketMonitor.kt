package com.fusion.melodyLinkNeo.ui.explorer

import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.protocol.api.MessageCodec
import java.time.Instant
import java.util.UUID

/** Direction of one captured GATT operation. */
enum class MonitorDirection { READ, WRITE, NOTIFY }

/**
 * One GATT operation recorded during a session. Payloads are copied so the monitor never aliases
 * a buffer owned by the BLE layer.
 */
data class MonitorEntry(
    val timestamp: Instant,
    val direction: MonitorDirection,
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val payload: ByteArray,
    val detail: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is MonitorEntry &&
        timestamp == other.timestamp && direction == other.direction && serviceUuid == other.serviceUuid &&
        characteristicUuid == other.characteristicUuid && detail == other.detail && payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * timestamp.hashCode() + direction.hashCode()) + serviceUuid.hashCode()) +
            characteristicUuid.hashCode()) + payload.contentHashCode()

    fun matches(query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return direction.name.lowercase().contains(needle) ||
            characteristicUuid.toString().contains(needle) ||
            serviceUuid.toString().contains(needle) ||
            payload.toHex().lowercase().contains(needle)
    }
}

/** One decoded protocol field, rendered as text by the debugger UI. */
data class DecodedField(val name: String, val value: String)

/**
 * Result of running a captured payload through the active Definition's protocol section.
 * [error] is populated (and [fields] empty) when the packet cannot be decoded.
 */
data class PacketDecode(
    val command: Int?,
    val sequence: Int?,
    val messageName: String?,
    val fields: List<DecodedField>,
    val error: String?,
)

/**
 * Decodes raw GATT payloads with the definition's `protocol` section. It only understands commands
 * the definition declares through its transactions; anything else is reported as raw hex plus a
 * reason instead of being guessed.
 */
class ProtocolDebugger(private val definition: LoadedDeviceDefinition) {
    private val factory = definition.protocolFactory()
    private val codec = factory.packetCodec
    private val messagesByCommand: Map<Int, String> = buildMap {
        definition.protocol.transactions.values.forEach { transaction ->
            transaction.requestMessage?.let { putIfAbsent(transaction.requestCommand, it) }
            transaction.expectedCommand?.let { command ->
                transaction.responseMessage?.let { putIfAbsent(command, it) }
            }
        }
    }

    fun decode(payload: ByteArray): PacketDecode {
        val packet = runCatching { codec.decode(payload) }.getOrElse { error ->
            return PacketDecode(null, null, null, emptyList(), "Not a packet: ${error.message}")
        }
        val messageName = messagesByCommand[packet.command]
            ?: return PacketDecode(packet.command, packet.sequence, null, emptyList(), "No message declared for command ${packet.command}")
        val message = runCatching { MessageCodec(factory.schema(messageName)).decode(packet.payload) }
            .getOrElse { error ->
                return PacketDecode(packet.command, packet.sequence, messageName, emptyList(), "Payload does not match '$messageName': ${error.message}")
            }
        val fields = factory.schema(messageName).fields.map { field ->
            DecodedField(field.name, formatValue(message.asMap()[field.name]))
        }
        return PacketDecode(packet.command, packet.sequence, messageName, fields, null)
    }

    private fun formatValue(value: Any?): String = when (value) {
        null -> "--"
        is ByteArray -> value.toHex()
        is com.fusion.melodyLinkNeo.protocol.api.EnumValue -> value.name?.let { "$it (${value.raw})" } ?: value.raw.toString()
        else -> value.toString()
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xff) }

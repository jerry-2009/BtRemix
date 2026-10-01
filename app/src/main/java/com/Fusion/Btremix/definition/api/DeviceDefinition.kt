package com.Fusion.Btremix.definition.api

import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.protocol.api.Endianness
import com.Fusion.Btremix.protocol.api.Message
import com.Fusion.Btremix.protocol.api.MessageCodec
import com.Fusion.Btremix.protocol.api.MessageSchema
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.SimplePacketCodec
import com.Fusion.Btremix.protocol.api.TransactionRequest
import com.Fusion.Btremix.protocol.api.ValueCodec
import java.util.UUID
import kotlin.time.Duration

enum class StateDefinitionType { BOOLEAN, INTEGER, NUMBER, STRING, ENUM, BYTES }

data class StateDefinition(
    val key: String,
    val type: StateDefinitionType,
    val defaultValue: com.Fusion.Btremix.device.runtime.StateValue? = null,
    val displayName: String? = null,
    val description: String? = null,
    val unit: String? = null,
    val enumValues: Map<String, String> = emptyMap(),
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
)

data class ActionParameterDefinition(
    val name: String,
    val type: StateDefinitionType,
    val required: Boolean = true,
    val displayName: String? = null,
    val enumValues: Map<String, String> = emptyMap(),
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
)

data class ActionDefinition(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val parameters: List<ActionParameterDefinition> = emptyList(),
    val resultState: String? = null,
)

sealed interface UiNode {
    val id: String?
    data class Column(override val id: String? = null, val children: List<UiNode> = emptyList()) : UiNode
    data class Section(val title: String, override val id: String? = null, val children: List<UiNode> = emptyList()) : UiNode
    data class Text(val text: String? = null, val state: String? = null, override val id: String? = null) : UiNode
    data class Value( val state: String, override val id: String? = null) : UiNode
    data class Switch(val state: String, val action: String, override val id: String? = null) : UiNode
    data class Slider(val state: String, val action: String, override val id: String? = null) : UiNode
    data class Button(val label: String, val action: String, val args: Map<String, com.Fusion.Btremix.device.runtime.StateValue> = emptyMap(), override val id: String? = null) : UiNode
    data class Segmented(val state: String, val action: String, val options: List<String>, override val id: String? = null) : UiNode
    data class Progress(val state: String, override val id: String? = null) : UiNode
}

data class UiSchema(val title: String? = null, val children: List<UiNode> = emptyList())

/** A stable contract shared by built-in and loaded definitions. */
interface DeviceDefinition {
    val id: String
    val displayName: String
}

data class DefinitionManifest(
    override val id: String,
    override val displayName: String,
    val version: String,
    val matchers: List<DeviceMatchRule> = emptyList(),
    val capabilities: Set<String> = emptySet(),
    val runtime: String? = null,
) : DeviceDefinition

sealed interface DeviceMatchRule {
    val priority: Int
    fun matches(scan: BleScanResult): Boolean

    data class NameExact(val value: String, override val priority: Int = 100) : DeviceMatchRule {
        override fun matches(scan: BleScanResult): Boolean = scan.device.name == value
    }

    data class NamePrefix(val value: String, override val priority: Int = 50) : DeviceMatchRule {
        override fun matches(scan: BleScanResult): Boolean = scan.device.name?.startsWith(value) == true
    }

    data class NameRegex(val pattern: String, override val priority: Int = 25) : DeviceMatchRule {
        private val regex = Regex(pattern)
        override fun matches(scan: BleScanResult): Boolean = scan.device.name?.let(regex::matches) == true
    }

    data class ServiceUuid(val uuid: UUID, override val priority: Int = 200) : DeviceMatchRule {
        override fun matches(scan: BleScanResult): Boolean = uuid in scan.serviceUuids
    }

    data class AddressRegex(val pattern: String, override val priority: Int = 150) : DeviceMatchRule {
        private val regex = Regex(pattern)
        override fun matches(scan: BleScanResult): Boolean = regex.matches(scan.device.address)
    }

    data class ManufacturerData(
        val companyId: Int,
        val prefix: ByteArray = byteArrayOf(),
        override val priority: Int = 300,
    ) : DeviceMatchRule {
        init {
            require(companyId in 0..0xffff) { "Manufacturer company id must fit UInt16" }
        }

        override fun matches(scan: BleScanResult): Boolean {
            val data = scan.manufacturerData[companyId] ?: return false
            return data.size >= prefix.size && data.copyOf(prefix.size).contentEquals(prefix)
        }

        override fun equals(other: Any?): Boolean = other is ManufacturerData &&
            companyId == other.companyId && prefix.contentEquals(other.prefix) && priority == other.priority

        override fun hashCode(): Int = 31 * (31 * companyId + prefix.contentHashCode()) + priority
    }
}

enum class ProtocolFieldType {
    UINT8, INT8, UINT16, INT16, UINT32, INT32, FLOAT32, BYTES, STRING, ENUM, BIT_FIELD, SLICE, CONCAT,
}

data class ProtocolFieldDefinition(
    val name: String,
    val type: ProtocolFieldType,
    val length: Int? = null,
    val offset: Int = 0,
    val charset: String = "UTF-8",
    val enumValues: Map<Int, String> = emptyMap(),
    val mask: Int? = null,
    val shift: Int? = null,
    val parts: List<ProtocolFieldDefinition> = emptyList(),
)

data class MessageDefinition(
    val name: String,
    val fields: List<ProtocolFieldDefinition>,
    val endianness: Endianness? = null,
)

data class PacketDefinition(val includesSequence: Boolean = true)

data class TransactionDefinition(
    val name: String,
    val requestCommand: Int,
    val requestMessage: String? = null,
    val expectedCommand: Int? = null,
    val timeout: Duration? = null,
    val retries: Int = 0,
)

data class ProtocolDefinition(
    val endianness: Endianness = Endianness.LITTLE,
    val packet: PacketDefinition = PacketDefinition(),
    val messages: Map<String, MessageDefinition> = emptyMap(),
    val transactions: Map<String, TransactionDefinition> = emptyMap(),
)

data class LoadedDeviceDefinition(
    val manifest: DefinitionManifest,
    val protocol: ProtocolDefinition = ProtocolDefinition(),
    val states: Map<String, StateDefinition> = emptyMap(),
    val actions: Map<String, ActionDefinition> = emptyMap(),
    val ui: UiSchema = UiSchema(),
) : DeviceDefinition by manifest {
    fun protocolFactory(): DefinitionProtocolFactory = DefinitionProtocolFactory(this)
}

/** Turns a validated protocol section into the generic Phase 3 codec objects. */
class DefinitionProtocolFactory(private val definition: LoadedDeviceDefinition) {
    val packetCodec: SimplePacketCodec = SimplePacketCodec(definition.protocol.packet.includesSequence)

    fun schema(name: String): MessageSchema {
        val message = definition.protocol.messages[name] ?: error("Unknown message '$name'")
        val endianness = message.endianness ?: definition.protocol.endianness
        return MessageSchema(message.fields.map { it.toField(endianness) }, endianness)
    }

    fun codec(name: String): MessageCodec = MessageCodec(schema(name))

    fun encodePacket(transactionName: String, message: Message? = null): Packet {
        val transaction = definition.protocol.transactions[transactionName]
            ?: error("Unknown transaction '$transactionName'")
        val payload = if (transaction.requestMessage == null) byteArrayOf()
        else codec(transaction.requestMessage).encode(requireNotNull(message) { "Message is required" })
        return Packet(transaction.requestCommand, payload = payload)
    }

    fun transaction(transactionName: String, message: Message? = null): TransactionRequest {
        val transaction = definition.protocol.transactions[transactionName]
            ?: error("Unknown transaction '$transactionName'")
        return TransactionRequest(
            request = encodePacket(transactionName, message),
            expectedCommand = transaction.expectedCommand,
            timeout = transaction.timeout,
            retries = transaction.retries,
        )
    }

    private fun ProtocolFieldDefinition.toField(endianness: Endianness) =
        com.Fusion.Btremix.protocol.api.Field(name, toCodec(endianness))

    @Suppress("UNCHECKED_CAST")
    private fun ProtocolFieldDefinition.toCodec(endianness: Endianness): ValueCodec<Any> = when (type) {
        ProtocolFieldType.UINT8 -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.uint8
        ProtocolFieldType.INT8 -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.int8
        ProtocolFieldType.UINT16 -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.uint16
        ProtocolFieldType.INT16 -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.int16
        ProtocolFieldType.UINT32 -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.uint32
        ProtocolFieldType.INT32 -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.int32
        ProtocolFieldType.FLOAT32 -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.float32
        ProtocolFieldType.BYTES -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.bytes(length)
        ProtocolFieldType.STRING -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.string(
            requireNotNull(length) { "String field '$name' needs length" },
            charset = resolveCharset(charset),
        )
        ProtocolFieldType.ENUM -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.enum(enumValues)
        ProtocolFieldType.BIT_FIELD -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.bitField(
            requireNotNull(mask) { "Bit field '$name' needs mask" }, shift ?: Integer.numberOfTrailingZeros(mask),
        )
        ProtocolFieldType.SLICE -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.slice(offset, requireNotNull(length))
        ProtocolFieldType.CONCAT -> com.Fusion.Btremix.protocol.api.ProtocolPrimitives.concat(
            *parts.map { it.toCodec(endianness) as ValueCodec<ByteArray> }.toTypedArray(),
        )
    } as ValueCodec<Any>
}

private fun resolveCharset(name: String): java.nio.charset.Charset = runCatching {
    java.nio.charset.Charset.forName(name)
}.getOrElse { error("Unsupported charset '$name'") }


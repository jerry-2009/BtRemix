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
import com.Fusion.Btremix.device.runtime.StateEntry
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.scripting.api.ScriptExpression
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import com.Fusion.Btremix.scripting.api.ScriptProgram

enum class StateDefinitionType { BOOLEAN, INTEGER, NUMBER, STRING, ENUM, BYTES }

/**
 * Definition Schema versions. Version 1 covers the original script-only schema; version 2 adds the
 * declarative bindings (`states.*.notify`, `actions.*.transaction`, `protocol.transport`); version 3
 * adds classic-Bluetooth byte-stream transports (`protocol.transport.type = rfcomm`,
 * `protocol.framing`, `protocol.initialize`, `states.*.notify.payloadType`,
 * `protocol.transactions.*.expectedPayloadType`).
 * Version 4 adds the optional `melody` section ([MelodySectionDefinition]) that advertises a
 * device to the ColorOS Melody panel; it is independent of the transport versions above.
 *
 * A definition that omits `manifest.schemaVersion` is treated as version 1 so every package written
 * before the declarative bindings keep working unchanged.
 */
object DefinitionSchema {
    const val VERSION_SCRIPT_ONLY: Int = 1
    const val VERSION_DECLARATIVE: Int = 2
    const val VERSION_STREAM: Int = 3
    const val VERSION_MELODY: Int = 4
    const val CURRENT: Int = VERSION_MELODY
    val SUPPORTED: IntRange = VERSION_SCRIPT_ONLY..VERSION_MELODY
}

/**
 * Declares that a state is driven by an inbound notification.
 *
 * GATT definitions subscribe to a characteristic (`service` + `characteristic`). Byte-stream
 * definitions (schema version 3) subscribe by payload type instead: every inbound protocol payload
 * whose first byte equals [payloadType] is offered to the state, and [condition] can further restrict
 * which of those payloads apply.
 *
 * [decode] is a Script expression evaluated with the incoming bytes bound to the variable `raw`;
 * omitting it stores the raw bytes (only valid for `bytes` states). The runtime keeps a continuous
 * subscription for as long as the session is ready.
 */
data class NotifyDefinition(
    val service: String? = null,
    val characteristic: String? = null,
    val decode: ScriptExpression? = null,
    /**
     * Byte-stream only: matches `payload[0]` of an inbound frame. Several types may share one
     * decode, for example a response type and its unsolicited notification counterpart.
     */
    val payloadTypes: Set<Int> = emptySet(),
    /** Byte-stream only: when present, the notification is ignored unless this expression is `true`. */
    val condition: ScriptExpression? = null,
)

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
    val notify: NotifyDefinition? = null,
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
    val script: ScriptProgram? = null,
    /** Declarative path: name of a `protocol.transactions` entry. Mutually exclusive with [script]. */
    val transaction: String? = null,
    /** Message field name -> expression evaluated against the action arguments. */
    val arguments: Map<String, ScriptExpression> = emptyMap(),
    /** Expression stored into [resultState] after a successful transaction. */
    val result: ScriptExpression? = null,
    /**
     * Byte-stream only: transactions to run, in order and best effort, right after this action's
     * write completes. Some peers acknowledge a SET at the framing layer but never publish the
     * resulting state, so state only becomes visible through a follow-up read; the read-back
     * responses travel the normal `states.*.notify` path.
     */
    val refresh: List<String> = emptyList(),
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
    val schemaVersion: Int = DefinitionSchema.VERSION_SCRIPT_ONLY,
    val matchers: List<DeviceMatchRule> = emptyList(),
    val capabilities: Set<String> = emptySet(),
    val runtime: String? = null,
    /**
     * Optional product metadata (DEVICE_CENTER_UI_PLAN §3.5A).
     *
     * These keys are additive and optional: the JSON codec already ignores unknown keys, so no
     * `schemaVersion` bump is needed and packages written before them keep loading unchanged. The
     * device artwork is *not* declared here - it is fixed to `assets/icon.png` inside the package
     * (D-UI-5).
     */
    val author: String? = null,
    val description: String? = null,
    val homepage: String? = null,
    val deviceType: String? = null,
    val minRuntime: String? = null,
) : DeviceDefinition

sealed interface DeviceMatchRule {
    val priority: Int

    /**
     * Neutral match (MELODY_BRIDGE_SPEC §11.3): name and address rules apply to every input kind,
     * while [ServiceUuid] / [ManufacturerData] only apply to [DeviceMatchInput.Advertisement].
     */
    fun matches(input: DeviceMatchInput): Boolean

    /** Scan-time convenience used by the BLE Explorer and existing tests. */
    fun matches(scan: BleScanResult): Boolean = matches(DeviceMatchInput.Advertisement(scan))

    data class NameExact(val value: String, override val priority: Int = 100) : DeviceMatchRule {
        override fun matches(input: DeviceMatchInput): Boolean = input.name == value
    }

    data class NamePrefix(val value: String, override val priority: Int = 50) : DeviceMatchRule {
        override fun matches(input: DeviceMatchInput): Boolean = input.name?.startsWith(value) == true
    }

    data class NameRegex(val pattern: String, override val priority: Int = 25) : DeviceMatchRule {
        private val regex = Regex(pattern)
        override fun matches(input: DeviceMatchInput): Boolean = input.name?.let(regex::matches) == true
    }

    data class ServiceUuid(val uuid: UUID, override val priority: Int = 200) : DeviceMatchRule {
        override fun matches(input: DeviceMatchInput): Boolean {
            val scan = (input as? DeviceMatchInput.Advertisement)?.scan ?: return false
            return uuid in scan.serviceUuids
        }
    }

    data class AddressRegex(val pattern: String, override val priority: Int = 150) : DeviceMatchRule {
        private val regex = Regex(pattern)
        override fun matches(input: DeviceMatchInput): Boolean = regex.matches(input.address)
    }

    data class ManufacturerData(
        val companyId: Int,
        val prefix: ByteArray = byteArrayOf(),
        override val priority: Int = 300,
    ) : DeviceMatchRule {
        init {
            require(companyId in 0..0xffff) { "Manufacturer company id must fit UInt16" }
        }

        override fun matches(input: DeviceMatchInput): Boolean {
            val scan = (input as? DeviceMatchInput.Advertisement)?.scan ?: return false
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

/** How a definition's protocol packets travel. */
enum class TransportType { GATT, RFCOMM }

/** Where a definition's protocol packets are written to and read from. */
data class TransportDefinition(
    val service: String,
    /** GATT only. `null` for [TransportType.RFCOMM], where [service] is the SPP service UUID. */
    val characteristic: String? = null,
    val withResponse: Boolean = true,
    val type: TransportType = TransportType.GATT,
    /** RFCOMM only: refuse to connect when the device is not bonded. */
    val bonded: Boolean = true,
)

/**
 * Declares the framing of a byte-stream transport.
 *
 * [codec] names a generic implementation registered in
 * [com.Fusion.Btremix.protocol.api.StreamCodecRegistry]; every protocol-specific detail (delimiter
 * bytes, escaping, checksum, field layout, link acknowledgement) is data here. Definitions can never
 * carry executable framing logic, and no vendor name appears in the runtime.
 */
data class FramingDefinition(
    val codec: String,
    val header: Int? = null,
    val trailer: Int? = null,
    val escape: Int? = null,
    val escapeMask: Int = 0xEF,
    /** `sum8`, `xor8` or `none`. */
    val checksum: String? = null,
    val messageTypeOffset: Int = 0,
    val sequenceOffset: Int? = 1,
    val lengthOffset: Int = 2,
    val lengthBytes: Int = 4,
    val lengthByteOrder: Endianness = Endianness.BIG,
    val acknowledgeMessageTypes: List<Int> = emptyList(),
    val acknowledgeReplyMessageType: Int? = null,
    /** `copy`, `complement`, `zero` or `one`. */
    val acknowledgeSequence: String = "complement",
) {
    /** Validated by the Definition validator; a malformed config fails here instead of on connect. */
    fun toSpec(): com.Fusion.Btremix.protocol.api.FramingSpec {
        val checksumSpec = checksum?.let { name ->
            val algorithm = when (name.lowercase()) {
                "sum8" -> com.Fusion.Btremix.protocol.api.ChecksumAlgorithm.SUM8
                "xor8" -> com.Fusion.Btremix.protocol.api.ChecksumAlgorithm.XOR8
                "none" -> com.Fusion.Btremix.protocol.api.ChecksumAlgorithm.NONE
                else -> error("Unknown checksum '$name'")
            }
            com.Fusion.Btremix.protocol.api.ChecksumSpec(algorithm)
        }
        val acknowledgement = acknowledgeReplyMessageType?.let { replyType ->
            com.Fusion.Btremix.protocol.api.AcknowledgementSpec(
                messageTypes = acknowledgeMessageTypes.toSet(),
                replyMessageType = replyType,
                sequence = when (acknowledgeSequence.lowercase()) {
                    "copy" -> com.Fusion.Btremix.protocol.api.AcknowledgementSequence.COPY
                    "complement" -> com.Fusion.Btremix.protocol.api.AcknowledgementSequence.COMPLEMENT
                    "zero" -> com.Fusion.Btremix.protocol.api.AcknowledgementSequence.ZERO
                    "one" -> com.Fusion.Btremix.protocol.api.AcknowledgementSequence.ONE
                    else -> error("Unknown acknowledgement sequence '$acknowledgeSequence'")
                },
            )
        }
        return com.Fusion.Btremix.protocol.api.FramingSpec(
            header = requireNotNull(header) { "Framing requires 'header'" },
            trailer = requireNotNull(trailer) { "Framing requires 'trailer'" },
            escape = escape,
            escapeMask = escapeMask,
            checksum = checksumSpec,
            layout = com.Fusion.Btremix.protocol.api.FramingLayout(
                messageTypeOffset = messageTypeOffset,
                sequenceOffset = sequenceOffset,
                lengthOffset = lengthOffset,
                lengthBytes = lengthBytes,
                lengthByteOrder = lengthByteOrder,
            ),
            acknowledgement = acknowledgement,
        )
    }
}

data class TransactionDefinition(
    val name: String,
    val requestCommand: Int,
    val requestMessage: String? = null,
    val expectedCommand: Int? = null,
    val responseMessage: String? = null,
    val timeout: Duration? = null,
    val retries: Int = 0,
    /**
     * Byte-stream only: the response is matched by `payload[0]`, because such protocols number their
     * responses independently of the request sequence. Several types may be accepted, for example a
     * response type and its unsolicited notification counterpart.
     */
    val expectedPayloadTypes: Set<Int> = emptySet(),
    /** Literal request payload (hex) used when [requestMessage] is absent. */
    val requestPayload: ByteArray? = null,
    /**
     * `false` for commands a peer only acknowledges at the framing layer (typical for SET
     * commands): the action writes the frame once and reports success without waiting for a
     * response payload.
     */
    val expectsResponse: Boolean = true,
)

data class ProtocolDefinition(
    val endianness: Endianness = Endianness.LITTLE,
    val packet: PacketDefinition = PacketDefinition(),
    val transport: TransportDefinition? = null,
    val framing: FramingDefinition? = null,
    val messages: Map<String, MessageDefinition> = emptyMap(),
    val transactions: Map<String, TransactionDefinition> = emptyMap(),
    /** Transaction names run, in order, after the session becomes ready. Failures are non-fatal. */
    val initialize: List<String> = emptyList(),
)

data class LoadedDeviceDefinition(
    val manifest: DefinitionManifest,
    val protocol: ProtocolDefinition = ProtocolDefinition(),
    val states: Map<String, StateDefinition> = emptyMap(),
    val actions: Map<String, ActionDefinition> = emptyMap(),
    val ui: UiSchema = UiSchema(),
    /** Optional ColorOS Melody advertisement; `null` keeps the definition out of Melody entirely. */
    val melody: MelodySectionDefinition? = null,
) : DeviceDefinition by manifest {
    fun protocolFactory(): DefinitionProtocolFactory = DefinitionProtocolFactory(this)
}

/**
 * Every declared state's default value as a runtime entry, sourced as [StateSource.INITIAL].
 *
 * This is the same seeding the Definition session factory performs on connect; it lets UI surfaces
 * (such as the developer-tools preview) render a definition without a connected device.
 */
fun LoadedDeviceDefinition.initialState(timestamp: Instant = Instant.now()): Map<String, StateEntry> =
    states.mapNotNull { (key, model) ->
        model.defaultValue?.let { default -> key to StateEntry(default, timestamp, StateSource.INITIAL) }
    }.toMap()

/** Turns a validated protocol section into the generic Phase 3 codec objects. */
class DefinitionProtocolFactory(private val definition: LoadedDeviceDefinition) {
    /**
     * GATT definitions use the simple `[command][sequence][payload]` framing; byte-stream
     * definitions use the codec named by `protocol.framing.codec`.
     */
    val packetCodec: com.Fusion.Btremix.protocol.api.PacketCodec =
        definition.protocol.framing
            ?.let { framing ->
                com.Fusion.Btremix.protocol.api.StreamCodecRegistry.create(framing.codec, framing.toSpec())
                    ?: error("Unknown framing codec '${framing.codec}'")
            }
            ?: SimplePacketCodec(definition.protocol.packet.includesSequence)

    fun schema(name: String): MessageSchema {
        val message = definition.protocol.messages[name] ?: error("Unknown message '$name'")
        val endianness = message.endianness ?: definition.protocol.endianness
        return MessageSchema(message.fields.map { it.toField(endianness) }, endianness)
    }

    fun codec(name: String): MessageCodec = MessageCodec(schema(name))

    fun encodePacket(transactionName: String, message: Message? = null): Packet {
        val transaction = definition.protocol.transactions[transactionName]
            ?: error("Unknown transaction '$transactionName'")
        val payload = when {
            transaction.requestMessage != null ->
                codec(transaction.requestMessage).encode(requireNotNull(message) { "Message is required" })
            transaction.requestPayload != null -> transaction.requestPayload.clone()
            else -> byteArrayOf()
        }
        return Packet(transaction.requestCommand, payload = payload)
    }

    fun transaction(transactionName: String, message: Message? = null): TransactionRequest {
        val transaction = definition.protocol.transactions[transactionName]
            ?: error("Unknown transaction '$transactionName'")
        val expectedPayloadTypes = transaction.expectedPayloadTypes
        return TransactionRequest(
            request = encodePacket(transactionName, message),
            expectedCommand = transaction.expectedCommand,
            timeout = transaction.timeout,
            retries = transaction.retries,
            // A byte-stream peer numbers its responses independently of the request, so the default
            // "same sequence" matcher would never match; the payload type is the discriminator.
            matcher = if (expectedPayloadTypes.isEmpty()) null else { response ->
                (transaction.expectedCommand == null || response.command == transaction.expectedCommand) &&
                    (response.payload.firstOrNull()?.toInt()?.and(0xff) in expectedPayloadTypes)
            },
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


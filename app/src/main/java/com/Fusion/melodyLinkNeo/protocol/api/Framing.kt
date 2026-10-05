package com.fusion.melodyLinkNeo.protocol.api

/**
 * Vendor-neutral description of a framed byte-stream protocol.
 *
 * Everything a Definition needs to describe a frame layout - delimiters, escaping, checksum,
 * header fields and link-level acknowledgement - is data. The runtime ships one generic
 * implementation that interprets this data, so no vendor naming ever enters the protocol layer.
 */
data class FramingSpec(
    /** Byte that starts a frame. */
    val header: Int,
    /** Byte that ends a frame. */
    val trailer: Int,
    /** Byte introducing an escape sequence, or `null` when the framing has no escaping. */
    val escape: Int? = null,
    /** Mask applied to an escaped byte (`escaped = byte and escapeMask`). */
    val escapeMask: Int = 0xEF,
    val checksum: ChecksumSpec? = null,
    val layout: FramingLayout = FramingLayout(),
    val acknowledgement: AcknowledgementSpec? = null,
) {
    init {
        require(header in 0..0xff) { "Frame header must fit UInt8" }
        require(trailer in 0..0xff) { "Frame trailer must fit UInt8" }
        require(escape == null || escape in 0..0xff) { "Escape byte must fit UInt8" }
        require(escapeMask in 0..0xff) { "Escape mask must fit UInt8" }
        require(escape == null || (escape != header && escape != trailer)) {
            "Escape byte must differ from header and trailer"
        }
        require(header != trailer) { "Frame header and trailer must differ" }
    }
}

/** Checksum algorithm applied to the frame body. */
enum class ChecksumAlgorithm { NONE, SUM8, XOR8 }

/** Which bytes the checksum covers. Kept as data so new scopes stay additive. */
enum class ChecksumCoverage { BODY }

data class ChecksumSpec(
    val algorithm: ChecksumAlgorithm,
    val coverage: ChecksumCoverage = ChecksumCoverage.BODY,
)

/** Fixed header fields inside the (unescaped) body. */
data class FramingLayout(
    val messageTypeOffset: Int = 0,
    /** Offset of the sequence byte, or `null` when the framing has no sequence. */
    val sequenceOffset: Int? = 1,
    val lengthOffset: Int = 2,
    val lengthBytes: Int = 4,
    val lengthByteOrder: Endianness = Endianness.BIG,
) {
    /** Offset at which the payload starts; derived so a Definition cannot make it inconsistent. */
    val payloadOffset: Int get() = lengthOffset + lengthBytes

    init {
        require(messageTypeOffset >= 0) { "Message type offset cannot be negative" }
        require(sequenceOffset == null || sequenceOffset >= 0) { "Sequence offset cannot be negative" }
        require(lengthOffset >= 0) { "Length offset cannot be negative" }
        require(lengthBytes in 1..4) { "Length field must be 1..4 bytes" }
    }
}

/** How a link-level acknowledgement reply is addressed. */
enum class AcknowledgementSequence { COPY, COMPLEMENT, ZERO, ONE }

data class AcknowledgementSpec(
    /** Inbound message types that must be acknowledged. */
    val messageTypes: Set<Int>,
    val replyMessageType: Int,
    val replyPayload: ByteArray = ByteArray(0),
    val sequence: AcknowledgementSequence = AcknowledgementSequence.COMPLEMENT,
) {
    init {
        require(messageTypes.isNotEmpty()) { "Acknowledgement spec needs at least one inbound message type" }
        require(messageTypes.all { it in 0..0xff }) { "Acknowledged message types must fit UInt8" }
        require(replyMessageType in 0..0xff) { "Acknowledgement reply type must fit UInt8" }
    }

    override fun equals(other: Any?): Boolean = other is AcknowledgementSpec &&
        messageTypes == other.messageTypes && replyMessageType == other.replyMessageType &&
        replyPayload.contentEquals(other.replyPayload) && sequence == other.sequence

    override fun hashCode(): Int =
        31 * (31 * (31 * messageTypes.hashCode() + replyMessageType) + replyPayload.contentHashCode()) + sequence.hashCode()
}

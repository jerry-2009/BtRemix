package com.Fusion.Btremix.protocol.codec.framed

import com.Fusion.Btremix.protocol.api.AcknowledgementSequence
import com.Fusion.Btremix.protocol.api.Checksum
import com.Fusion.Btremix.protocol.api.ChecksumRegistry
import com.Fusion.Btremix.protocol.api.Endianness
import com.Fusion.Btremix.protocol.api.FrameAccumulator
import com.Fusion.Btremix.protocol.api.FrameSplitterRegistry
import com.Fusion.Btremix.protocol.api.FramingSpec
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.StreamCodec

/** Raised when a byte sequence does not match the Definition's declared framing. */
class FramedCodecException(message: String) : IllegalArgumentException(message)

/**
 * Generic implementation of a delimited, escaped, checksummed byte-stream frame.
 *
 * Layout (all offsets from [FramingSpec.layout]):
 *
 * ```text
 * header | escape(body) | escape(checksum)? | trailer
 * body   = messageType | sequence? | length | payload
 * ```
 *
 * Everything else - delimiter bytes, escape byte and mask, checksum algorithm, length width/order
 * and the acknowledgement rule - comes from the Definition, so a vendor protocol is configuration
 * rather than Kotlin. This class contains no protocol-specific constant.
 */
class FramedStreamCodec(private val spec: FramingSpec) : StreamCodec {
    override val name: String = NAME
    private val header: Int = requireNotNull(spec.header) { "Framed codec requires a header" }
    private val trailer: Int = requireNotNull(spec.trailer) { "Framed codec requires a trailer" }
    private val checksum: Checksum? = spec.checksum?.let(ChecksumRegistry::create)
    private val checksumBytes: Int get() = (checksum?.widthBits ?: 0) / 8

    override fun accumulator(): FrameAccumulator = FrameSplitterRegistry.create(spec.splitter.function, spec)
        ?: error(
            "Unknown frame splitter '${spec.splitter.function}', supported " +
                FrameSplitterRegistry.names.sorted(),
        )

    override fun encode(packet: Packet): ByteArray {
        val layout = spec.layout
        val sequenceOffset = layout.sequenceOffset
        if (sequenceOffset != null) {
            requireNotNull(packet.sequence) { "Framing declares a sequence byte but the packet has none" }
        }
        val length = packet.payload.size
        val bodySize = layout.payloadOffset + length
        val body = ByteArray(bodySize)
        body[layout.messageTypeOffset] = packet.command.toByte()
        sequenceOffset?.let { offset -> body[offset] = packet.sequence!!.toByte() }
        writeLength(body, layout.lengthOffset, layout.lengthBytes, layout.lengthByteOrder, length)
        packet.payload.copyInto(body, layout.payloadOffset)

        val checksumValue = checksum?.let { it.calculate(body) }
        val escapedBody = escape(body)
        val rawChecksum = checksumValue?.takeIf { checksumBytes > 0 }?.let {
            writeInteger(it, checksumBytes, spec.checksum!!.outputByteOrder)
        }
        val escapedChecksum = rawChecksum?.let(::escape)
        val frame = ByteArray(
            2 + escapedBody.size + (escapedChecksum?.size ?: 0),
        )
        frame[0] = header.toByte()
        escapedBody.copyInto(frame, 1)
        escapedChecksum?.copyInto(frame, 1 + escapedBody.size)
        frame[frame.lastIndex] = trailer.toByte()
        return frame
    }

    override fun decode(bytes: ByteArray): Packet {
        if (bytes.isEmpty()) throw FramedCodecException("Frame is empty")
        if ((bytes.first().toInt() and 0xff) != header) {
            throw FramedCodecException("Frame does not start with 0x%02X".format(header))
        }
        if ((bytes.last().toInt() and 0xff) != trailer) {
            throw FramedCodecException("Frame does not end with 0x%02X".format(trailer))
        }
        val inner = unescape(bytes.copyOfRange(1, bytes.size - 1))
        val layout = spec.layout
        if (inner.size < layout.payloadOffset + checksumBytes) {
            throw FramedCodecException("Frame body is truncated: ${inner.size} bytes")
        }
        val body = inner.copyOfRange(0, inner.size - checksumBytes)
        if (checksum != null && checksumBytes > 0) {
            val expected = checksum.calculate(body)
            val actual = readInteger(
                inner,
                inner.size - checksumBytes,
                checksumBytes,
                spec.checksum!!.outputByteOrder,
            )
            if (actual != expected) throw FramedCodecException("Checksum mismatch: $actual != $expected")
        }
        val length = readLength(body, layout.lengthOffset, layout.lengthBytes, layout.lengthByteOrder)
        val available = body.size - layout.payloadOffset
        if (length != available) {
            throw FramedCodecException("Declared payload length $length does not match $available")
        }
        return Packet(
            command = body[layout.messageTypeOffset].toInt() and 0xff,
            sequence = layout.sequenceOffset?.let { offset -> body[offset].toInt() and 0xff },
            payload = body.copyOfRange(layout.payloadOffset, body.size),
        )
    }

    override fun automaticReplies(packet: Packet): List<Packet> {
        val ack = spec.acknowledgement ?: return emptyList()
        if (packet.command !in ack.messageTypes) return emptyList()
        val sequence = when (ack.sequence) {
            AcknowledgementSequence.COPY -> packet.sequence
            AcknowledgementSequence.COMPLEMENT -> packet.sequence?.let { (1 - it) and 0xff }
            AcknowledgementSequence.ZERO -> 0
            AcknowledgementSequence.ONE -> 1
        }
        return listOf(Packet(ack.replyMessageType, sequence, ack.replyPayload.clone()))
    }

    private fun escape(bytes: ByteArray): ByteArray {
        val escapeByte = spec.escape ?: return bytes
        val out = ArrayList<Byte>(bytes.size + 4)
        for (raw in bytes) {
            val value = raw.toInt() and 0xff
            if (value == escapeByte || value == header || value == trailer) {
                out += escapeByte.toByte()
                out += (value and spec.escapeMask).toByte()
            } else {
                out += raw
            }
        }
        return out.toByteArray()
    }

    private fun unescape(bytes: ByteArray): ByteArray {
        val escapeByte = spec.escape ?: return bytes
        val out = ArrayList<Byte>(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val value = bytes[index].toInt() and 0xff
            if (value == escapeByte) {
                val next = bytes.getOrNull(index + 1)
                    ?: throw FramedCodecException("Escaped byte is missing its payload")
                // Restores the original byte: escaped = byte and mask, so byte = escaped or complement(mask).
                out += ((next.toInt() and 0xff) or (spec.escapeMask.inv() and 0xff)).toByte()
                index += 2
            } else {
                out += bytes[index]
                index++
            }
        }
        return out.toByteArray()
    }

    private fun writeLength(body: ByteArray, offset: Int, width: Int, order: Endianness, value: Int) {
        for (index in 0 until width) {
            val shift = if (order == Endianness.BIG) (width - 1 - index) * 8 else index * 8
            body[offset + index] = (value ushr shift).toByte()
        }
    }

    private fun readLength(body: ByteArray, offset: Int, width: Int, order: Endianness): Int {
        return readInteger(body, offset, width, order)
    }

    private fun writeInteger(value: Int, width: Int, order: Endianness): ByteArray = ByteArray(width).also { bytes ->
        writeLength(bytes, 0, width, order, value)
    }

    private fun readInteger(bytes: ByteArray, offset: Int, width: Int, order: Endianness): Int {
        var value = 0
        for (index in 0 until width) {
            val shift = if (order == Endianness.BIG) (width - 1 - index) * 8 else index * 8
            value = value or ((bytes[offset + index].toInt() and 0xff) shl shift)
        }
        return value
    }

    companion object {
        const val NAME: String = "framed"
    }
}

package com.fusion.melodyLinkNeo.protocol.codec.framed

import com.fusion.melodyLinkNeo.protocol.api.AcknowledgementSequence
import com.fusion.melodyLinkNeo.protocol.api.ChecksumAlgorithm
import com.fusion.melodyLinkNeo.protocol.api.Endianness
import com.fusion.melodyLinkNeo.protocol.api.FrameAccumulator
import com.fusion.melodyLinkNeo.protocol.api.FramingSpec
import com.fusion.melodyLinkNeo.protocol.api.Packet
import com.fusion.melodyLinkNeo.protocol.api.StreamCodec

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

    override fun accumulator(): FrameAccumulator =
        DelimitedFrameAccumulator(spec.header, spec.trailer)

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

        val checksum = if (checksumPresent) checksum(spec.checksum!!.algorithm, body) else null
        val escapedBody = escape(body)
        val escapedChecksum = checksum?.let { escape(byteArrayOf(it.toByte())) }
        val frame = ByteArray(
            2 + escapedBody.size + (escapedChecksum?.size ?: 0),
        )
        frame[0] = spec.header.toByte()
        escapedBody.copyInto(frame, 1)
        escapedChecksum?.copyInto(frame, 1 + escapedBody.size)
        frame[frame.lastIndex] = spec.trailer.toByte()
        return frame
    }

    override fun decode(bytes: ByteArray): Packet {
        if (bytes.isEmpty()) throw FramedCodecException("Frame is empty")
        if ((bytes.first().toInt() and 0xff) != spec.header) {
            throw FramedCodecException("Frame does not start with 0x%02X".format(spec.header))
        }
        if ((bytes.last().toInt() and 0xff) != spec.trailer) {
            throw FramedCodecException("Frame does not end with 0x%02X".format(spec.trailer))
        }
        val inner = unescape(bytes.copyOfRange(1, bytes.size - 1))
        val checksumSize = if (checksumPresent) 1 else 0
        val layout = spec.layout
        if (inner.size < layout.payloadOffset + checksumSize) {
            throw FramedCodecException("Frame body is truncated: ${inner.size} bytes")
        }
        val body = inner.copyOfRange(0, inner.size - checksumSize)
        if (checksumPresent) {
            val expected = checksum(spec.checksum!!.algorithm, body)
            val actual = inner.last().toInt() and 0xff
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
            if (value == escapeByte || value == spec.header || value == spec.trailer) {
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

    private fun checksum(algorithm: ChecksumAlgorithm, body: ByteArray): Int = when (algorithm) {
        ChecksumAlgorithm.NONE -> 0
        ChecksumAlgorithm.SUM8 -> body.fold(0) { sum, byte -> (sum + (byte.toInt() and 0xff)) and 0xff }
        ChecksumAlgorithm.XOR8 -> body.fold(0) { acc, byte -> acc xor (byte.toInt() and 0xff) }
    }

    private fun writeLength(body: ByteArray, offset: Int, width: Int, order: Endianness, value: Int) {
        for (index in 0 until width) {
            val shift = if (order == Endianness.BIG) (width - 1 - index) * 8 else index * 8
            body[offset + index] = (value ushr shift).toByte()
        }
    }

    private fun readLength(body: ByteArray, offset: Int, width: Int, order: Endianness): Int {
        var value = 0
        for (index in 0 until width) {
            val shift = if (order == Endianness.BIG) (width - 1 - index) * 8 else index * 8
            value = value or ((body[offset + index].toInt() and 0xff) shl shift)
        }
        return value
    }

    private val checksumPresent: Boolean
        get() = spec.checksum != null && spec.checksum!!.algorithm != ChecksumAlgorithm.NONE

    companion object {
        const val NAME: String = "framed"
    }
}

/**
 * Splits a byte stream into delimited frames.
 *
 * Escaping guarantees payload bytes never contain a raw delimiter, so scanning for the header and
 * the trailer is enough. Bytes before the first header are discarded, which keeps a noisy reconnect
 * from poisoning the next frame.
 */
class DelimitedFrameAccumulator(
    private val header: Int,
    private val trailer: Int,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) : FrameAccumulator {
    private var buffer = ByteArray(0)

    override fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        val combined = ByteArray(buffer.size + chunk.size)
        buffer.copyInto(combined, 0)
        chunk.copyInto(combined, buffer.size)

        val frames = mutableListOf<ByteArray>()
        var frameStart = -1
        combined.forEachIndexed { index, raw ->
            val value = raw.toInt() and 0xff
            if (frameStart < 0) {
                if (value == header) frameStart = index
            } else if (value == trailer) {
                frames += combined.copyOfRange(frameStart, index + 1)
                frameStart = -1
            }
        }
        val remainder = if (frameStart >= 0) combined.copyOfRange(frameStart, combined.size) else ByteArray(0)
        buffer = if (remainder.size > maxFrameBytes) ByteArray(0) else remainder
        return frames
    }

    override fun reset() {
        buffer = ByteArray(0)
    }

    private companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 8192
    }
}

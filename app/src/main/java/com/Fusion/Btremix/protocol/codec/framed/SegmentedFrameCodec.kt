package com.Fusion.Btremix.protocol.codec.framed

import com.Fusion.Btremix.protocol.api.Checksum
import com.Fusion.Btremix.protocol.api.ChecksumCoverage
import com.Fusion.Btremix.protocol.api.ChecksumRegistry
import com.Fusion.Btremix.protocol.api.Endianness
import com.Fusion.Btremix.protocol.api.FrameAccumulator
import com.Fusion.Btremix.protocol.api.FrameLayoutSpec
import com.Fusion.Btremix.protocol.api.FrameLayoutRules
import com.Fusion.Btremix.protocol.api.FrameSegmentRole
import com.Fusion.Btremix.protocol.api.FrameSplitterRegistry
import com.Fusion.Btremix.protocol.api.FramingSpec
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.SegmentedFramingSpec
import com.Fusion.Btremix.protocol.api.StreamCodec
import com.Fusion.Btremix.protocol.api.jsonName

/**
 * Vendor-neutral codec for ordered segment tables.
 *
 * Requests are encoded with the request table by [encode], responses are decoded with the response
 * table by [decode]. The explicit direction methods are available to diagnostics and tests that need
 * to inspect the other direction.
 */
class SegmentedFrameCodec(private val spec: FramingSpec) : StreamCodec {
    override val name: String = NAME
    private val layouts: SegmentedFramingSpec = requireNotNull(spec.segmented) {
        "Segmented codec requires request/response layouts"
    }
    private val checksumSpec = spec.checksum
    private val checksum: Checksum? = checksumSpec?.let(ChecksumRegistry::create)
    private val checksumWidthBytes: Int = (checksum?.widthBits ?: 0) / 8

    init {
        require(checksumSpec == null || checksumSpec.coverage == ChecksumCoverage.SEGMENTS) {
            "Segmented codecs require checksum coverage 'segments'"
        }
        val (requestIssues, responseIssues) = listOf(layouts.request, layouts.response).map { layout ->
            FrameLayoutRules.validate(layout, checksumWidthBytes, checksumSpec?.outputByteOrder)
        }
        require(requestIssues.isEmpty()) {
            "Invalid request layout: ${requestIssues.joinToString { "${it.parameter} ${it.message}" }}"
        }
        require(responseIssues.isEmpty()) {
            "Invalid response layout: ${responseIssues.joinToString { "${it.parameter} ${it.message}" }}"
        }
    }

    val requestCommandWidthBytes: Int? = layouts.request.commandWidthBytes
    val responseCommandWidthBytes: Int? = layouts.response.commandWidthBytes

    override fun accumulator(): FrameAccumulator =
        FrameSplitterRegistry.create(spec.splitter.function, spec)
            ?: error(
                "Unknown frame splitter '${spec.splitter.function}', supported " +
                    FrameSplitterRegistry.names.sorted(),
            )

    override fun encode(packet: Packet): ByteArray = encodeRequest(packet)

    override fun decode(bytes: ByteArray): Packet = decodeResponse(bytes)

    fun encodeRequest(packet: Packet): ByteArray = encodeWith(layouts.request, packet)

    fun encodeResponse(packet: Packet): ByteArray = encodeWith(layouts.response, packet)

    fun decodeResponse(bytes: ByteArray): Packet = decodeWith(layouts.response, bytes)

    fun decodeRequest(bytes: ByteArray): Packet = decodeWith(layouts.request, bytes)

    private fun encodeWith(layout: FrameLayoutSpec, packet: Packet): ByteArray {
        val payload = packet.payload
        val initial = layout.resolvedOffsets(payload.size, checksumWidthBytes)
        val frame = ByteArray(initial.totalBytes)

        layout.segments.forEachIndexed { index, segment ->
            val offset = initial[segment.role] ?: offsetOf(initial, index)
            when (segment.role) {
                FrameSegmentRole.MAGIC,
                FrameSegmentRole.CONSTANT,
                -> requireNotNull(segment.constant).copyInto(frame, offset)

                FrameSegmentRole.COMMAND -> writeInteger(
                    frame,
                    offset,
                    requireNotNull(segment.width),
                    segment.order,
                    packet.command,
                )

                FrameSegmentRole.SEQUENCE -> writeInteger(
                    frame,
                    offset,
                    requireNotNull(segment.width),
                    segment.order,
                    requireNotNull(packet.sequence) { "Frame layout declares a sequence but packet has none" },
                )

                FrameSegmentRole.PAYLOAD -> payload.copyInto(frame, offset)
                FrameSegmentRole.OPAQUE,
                FrameSegmentRole.LENGTH,
                FrameSegmentRole.CHECKSUM,
                -> Unit
            }
        }

        val lengthValue = layout.segments.indices
            .filter { layout.segments[it].includeInLength }
            .sumOf(initial::width)
        val lengthSegment = layout.segments.indexOfFirst { it.role == FrameSegmentRole.LENGTH }
        writeInteger(
            frame,
            offsetOf(initial, lengthSegment),
            requireNotNull(layout.segments[lengthSegment].width),
            layout.segments[lengthSegment].order,
            lengthValue,
        )

        val checksumSegmentIndex = layout.segments.indexOfFirst { it.role == FrameSegmentRole.CHECKSUM }
        if (checksum != null && checksumSegmentIndex >= 0) {
            val covered = coverageBytes(layout, initial, frame)
            val value = checksum.calculate(covered)
            val segment = layout.segments[checksumSegmentIndex]
            writeInteger(
                frame,
                offsetOf(initial, checksumSegmentIndex),
                requireNotNull(segment.width),
                requireNotNull(checksumSpec).outputByteOrder,
                value,
            )
        }
        return frame
    }

    private fun decodeWith(layout: FrameLayoutSpec, bytes: ByteArray): Packet {
        val fixedBytes = layout.segments.sumOf { segment ->
            if (segment.role == FrameSegmentRole.PAYLOAD) 0 else segment.width ?: 0
        }
        val fixedLengthCoverage = layout.segments.sumOf { segment ->
            when {
                !segment.includeInLength -> 0
                segment.role == FrameSegmentRole.PAYLOAD -> 0
                else -> segment.width ?: 0
            }
        }
        val lengthIndex = layout.segments.indexOfFirst { it.role == FrameSegmentRole.LENGTH }
        require(lengthIndex >= 0) { "Frame layout has no length segment" }
        val lengthWidth = requireNotNull(layout.segments[lengthIndex].width)
        val lengthOffset = layout.segments.take(lengthIndex).sumOf { it.width ?: 0 }
        if (bytes.size < lengthOffset + lengthWidth) {
            throw SegmentedCodecException("Frame is shorter than its length field")
        }
        val lengthValue = readInteger(
            bytes,
            lengthOffset,
            lengthWidth,
            layout.segments[lengthIndex].order,
        )
        val payloadWidth = lengthValue - fixedLengthCoverage
        if (payloadWidth < 0) {
            throw SegmentedCodecException(
                "Declared length $lengthValue is smaller than the $fixedLengthCoverage-byte fixed span",
            )
        }
        val resolved = layout.resolvedOffsets(payloadWidth, checksumWidthBytes)
        if (resolved.totalBytes != bytes.size) {
            throw SegmentedCodecException(
                "Declared frame size ${resolved.totalBytes} does not match ${bytes.size} bytes",
            )
        }
        if (resolved.totalBytes != fixedBytes + payloadWidth) {
            throw SegmentedCodecException("Length field does not describe the frame span")
        }

        layout.segments.forEachIndexed { index, segment ->
            if (segment.role == FrameSegmentRole.MAGIC || segment.role == FrameSegmentRole.CONSTANT) {
                val expected = requireNotNull(segment.constant)
                val actual = bytes.copyOfRange(offsetOf(resolved, index), offsetOf(resolved, index) + expected.size)
                if (!actual.contentEquals(expected)) {
                    throw SegmentedCodecException(
                        "${segment.role.jsonName} mismatch at bytes ${offsetOf(resolved, index)}.." +
                            "${offsetOf(resolved, index) + expected.size - 1}",
                    )
                }
            }
        }

        if (checksum != null) {
            val checksumIndex = layout.segments.indexOfFirst { it.role == FrameSegmentRole.CHECKSUM }
            if (checksumIndex < 0) throw SegmentedCodecException("Frame layout has no checksum segment")
            val segment = layout.segments[checksumIndex]
            val offset = offsetOf(resolved, checksumIndex)
            val actual = readInteger(
                bytes,
                offset,
                requireNotNull(segment.width),
                requireNotNull(checksumSpec).outputByteOrder,
            )
            val covered = coverageBytes(layout, resolved, bytes)
            val expected = checksum.calculate(covered)
            if (actual != expected) {
                throw SegmentedCodecException("Checksum mismatch: ${actual.hex()} != ${expected.hex()}")
            }
        }

        val commandIndex = layout.segments.indexOfFirst { it.role == FrameSegmentRole.COMMAND }
        if (commandIndex < 0) throw SegmentedCodecException("Frame layout has no command segment")
        val commandSegment = layout.segments[commandIndex]
        val command = readInteger(
            bytes,
            offsetOf(resolved, commandIndex),
            requireNotNull(commandSegment.width),
            commandSegment.order,
        )
        val sequenceIndex = layout.segments.indexOfFirst { it.role == FrameSegmentRole.SEQUENCE }
        val sequence = sequenceIndex.takeIf { it >= 0 }?.let { index ->
            readInteger(
                bytes,
                offsetOf(resolved, index),
                requireNotNull(layout.segments[index].width),
                layout.segments[index].order,
            )
        }
        val payloadIndex = layout.segments.indexOfFirst { it.role == FrameSegmentRole.PAYLOAD }
        val payloadOffset = offsetOf(resolved, payloadIndex)
        return Packet(
            command = command,
            sequence = sequence,
            payload = bytes.copyOfRange(payloadOffset, payloadOffset + payloadWidth),
        )
    }

    /**
     * Encodes and decodes the checksum span. On encode the length field is already populated. On
     * decode the wire bytes are used as-is so a corrupted length cannot accidentally be excluded.
     */
    private fun coverageBytes(
        layout: FrameLayoutSpec,
        resolved: com.Fusion.Btremix.protocol.api.ResolvedFrameLayout,
        bytes: ByteArray,
    ): ByteArray {
        val parts = layout.segments.indices
            .filter { layout.segments[it].includeInChecksum }
            .map { index -> bytes.copyOfRange(resolved.span(index).first, resolved.span(index).last + 1) }
        if (parts.isEmpty()) {
            throw SegmentedCodecException("Checksum coverage selects no segments")
        }
        return ByteArray(parts.sumOf { it.size }).also { output ->
            var offset = 0
            parts.forEach { part ->
                part.copyInto(output, offset)
                offset += part.size
            }
        }
    }

    private fun offsetOf(resolved: com.Fusion.Btremix.protocol.api.ResolvedFrameLayout, segmentIndex: Int): Int =
        if (segmentIndex == 0) 0 else (0 until segmentIndex).sumOf { resolved.width(it) }

    private fun writeInteger(bytes: ByteArray, offset: Int, width: Int, order: Endianness, value: Int) {
        val maximum = if (width == 4) 0xffffffffL else (1L shl (width * 8)) - 1
        require(value.toLong() in 0..maximum) { "Value $value does not fit in $width bytes" }
        repeat(width) { index ->
            val shift = if (order == Endianness.BIG) (width - 1 - index) * 8 else index * 8
            bytes[offset + index] = (value ushr shift).toByte()
        }
    }

    private fun readInteger(bytes: ByteArray, offset: Int, width: Int, order: Endianness): Int {
        var value = 0
        repeat(width) { index ->
            val shift = if (order == Endianness.BIG) (width - 1 - index) * 8 else index * 8
            value = value or ((bytes[offset + index].toInt() and 0xff) shl shift)
        }
        return value
    }

    private fun Int.hex(): String = "0x%0${checksumWidthBytes * 2}X".format(this)

    companion object {
        const val NAME: String = "segmented"
    }
}

class SegmentedCodecException(message: String) : IllegalArgumentException(message)

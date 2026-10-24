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

    override fun accumulator(): FrameAccumulator {
        if (layouts.request != layouts.response) {
            return DirectionalLengthPrefixedFrameSplitter(
                request = layouts.request,
                response = layouts.response,
                maxFrameBytes = spec.splitter.maxFrameBytes,
                acceptRequest = { bytes -> runCatching { decodeRequest(bytes) }.isSuccess },
                acceptResponse = { bytes -> runCatching { decodeResponse(bytes) }.isSuccess },
            )
        }
        return FrameSplitterRegistry.create(spec.splitter.function, spec)
            ?: error(
                "Unknown frame splitter '${spec.splitter.function}', supported " +
                    FrameSplitterRegistry.names.sorted(),
            )
    }

    override fun encode(packet: Packet): ByteArray = encodeRequest(packet)

    /**
     * Inbound direction is discovered from the declared layouts. P2 responses remain the first
     * choice; if that layout does not fit, a request layout is tried for device-initiated frames.
     */
    override fun decode(bytes: ByteArray): Packet = try {
        decodeResponse(bytes)
    } catch (responseError: IllegalArgumentException) {
        if (layouts.request == layouts.response) throw responseError
        try {
            decodeRequest(bytes)
        } catch (_: IllegalArgumentException) {
            throw responseError
        }
    }

    fun encodeRequest(packet: Packet): ByteArray = encodeWith(layouts.request, packet)

    fun encodeResponse(packet: Packet): ByteArray = encodeWith(layouts.response, packet)

    fun decodeResponse(bytes: ByteArray): Packet = decodeWith(layouts.response, bytes)

    fun decodeRequest(bytes: ByteArray): Packet = decodeWith(layouts.request, bytes)

    private fun encodeWith(layout: FrameLayoutSpec, packet: Packet): ByteArray {
        val payload = packet.payload
        val initial = layout.resolvedOffsets(payload.size, checksumWidthBytes)
        val frame = ByteArray(initial.totalBytes)

        layout.segments.forEachIndexed { index, segment ->
            val offset = offsetOf(initial, index)
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
                val accepted = segment.acceptedConstants()
                val actual = bytes.copyOfRange(
                    offsetOf(resolved, index),
                    offsetOf(resolved, index) + (segment.width ?: 0),
                )
                if (accepted.none { actual.contentEquals(it) }) {
                    throw SegmentedCodecException(
                        "${segment.role.jsonName} mismatch at bytes ${offsetOf(resolved, index)}.." +
                            "${offsetOf(resolved, index) + actual.size - 1}",
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

/**
 * A length splitter for protocols whose request and response headers have different fixed sizes.
 *
 * The same length field can have a different absolute offset in each direction. A candidate is only
 * emitted after its full layout and checksum decode successfully, which avoids treating a response's
 * command byte as a request length when both directions share one magic.
 */
private class DirectionalLengthPrefixedFrameSplitter(
    private val request: FrameLayoutSpec,
    private val response: FrameLayoutSpec,
    private val maxFrameBytes: Int,
    private val acceptRequest: (ByteArray) -> Boolean,
    private val acceptResponse: (ByteArray) -> Boolean,
) : FrameAccumulator {
    private val magic = requireNotNull(request.segments.first().constant).clone()
    private var buffer = ByteArray(0)

    init {
        require(maxFrameBytes > 0)
        require(magic.isNotEmpty())
        require(response.segments.firstOrNull()?.constant?.contentEquals(magic) == true) {
            "Request and response layouts must share a magic for directional splitting"
        }
    }

    override fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        buffer = if (buffer.isEmpty()) chunk.clone() else buffer + chunk
        return parseAll()
    }

    override fun reject(frame: ByteArray): List<ByteArray> {
        val retained = buffer
        buffer = ByteArray(0)
        if (frame.size <= 1) {
            buffer = retained
            return emptyList()
        }
        buffer = frame + retained
        return parseAll(startSearchAt = 1)
    }

    override fun reset() {
        buffer = ByteArray(0)
    }

    private fun parseAll(startSearchAt: Int = 0): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var searchFrom = startSearchAt
        while (true) {
            val start = findMagic(searchFrom)
            if (start < 0) {
                keepMagicTail()
                return frames
            }
            val requestSize = candidateSize(request, start)
            val responseSize = candidateSize(response, start)
            val acceptedSize = when {
                requestSize != null &&
                    requestSize in 1..(buffer.size - start) &&
                    acceptRequest(buffer.copyOfRange(start, start + requestSize)) -> requestSize
                responseSize != null &&
                    responseSize in 1..(buffer.size - start) &&
                    acceptResponse(buffer.copyOfRange(start, start + responseSize)) -> responseSize
                else -> null
            }
            if (acceptedSize != null) {
                frames += buffer.copyOfRange(start, start + acceptedSize)
                searchFrom = start + acceptedSize
                continue
            }
            val completeCandidates = listOfNotNull(requestSize, responseSize)
                .filter { size -> size in 1..(buffer.size - start) }
            if (completeCandidates.isNotEmpty()) {
                // Emit one structurally complete candidate so StreamTransport can report the bad
                // frame and call reject(). The shorter candidate preserves a possible magic prefix.
                val rejectedSize = completeCandidates.min()
                frames += buffer.copyOfRange(start, start + rejectedSize)
                searchFrom = start + rejectedSize
                continue
            }
            val awaitingHeaderOrFrame = requestSize == null ||
                responseSize == null ||
                listOf(requestSize, responseSize).any { size -> size > buffer.size - start }
            if (awaitingHeaderOrFrame) {
                buffer = buffer.copyOfRange(start, buffer.size)
                return frames
            }
            searchFrom = start + 1
        }
    }

    private fun candidateSize(layout: FrameLayoutSpec, start: Int): Int? {
        val lengthIndex = layout.segments.indexOfFirst { it.role == FrameSegmentRole.LENGTH }
        if (lengthIndex < 0) return null
        val lengthOffset = layout.segments.take(lengthIndex).sumOf { it.width ?: 0 }
        val lengthSegment = layout.segments[lengthIndex]
        val lengthWidth = lengthSegment.width ?: return null
        if (buffer.size - start < lengthOffset + lengthWidth) return null
        var length = 0
        repeat(lengthWidth) { index ->
            val shift = if (lengthSegment.order == Endianness.BIG) {
                (lengthWidth - 1 - index) * 8
            } else {
                index * 8
            }
            length = length or ((buffer[start + lengthOffset + index].toInt() and 0xff) shl shift)
        }
        val fixedInside = layout.segments.sumOf { segment ->
            if (segment.includeInLength && segment.role != FrameSegmentRole.PAYLOAD) segment.width ?: 0 else 0
        }
        if (length < fixedInside) return -1
        val fixedOutside = layout.segments.sumOf { segment ->
            when {
                segment.includeInLength -> 0
                segment.role == FrameSegmentRole.PAYLOAD -> 0
                else -> segment.width ?: 0
            }
        }
        return (fixedOutside + length).takeIf { it <= maxFrameBytes } ?: -1
    }

    private fun findMagic(from: Int): Int {
        if (buffer.size < magic.size) return -1
        for (start in from..buffer.size - magic.size) {
            if (magic.indices.all { buffer[start + it] == magic[it] }) return start
        }
        return -1
    }

    private fun keepMagicTail() {
        val keep = minOf(buffer.size, magic.size - 1)
        buffer = if (keep == 0) ByteArray(0) else buffer.copyOfRange(buffer.size - keep, buffer.size)
    }
}

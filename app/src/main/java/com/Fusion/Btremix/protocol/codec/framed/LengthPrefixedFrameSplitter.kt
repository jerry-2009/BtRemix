package com.Fusion.Btremix.protocol.codec.framed

import com.Fusion.Btremix.protocol.api.Endianness
import com.Fusion.Btremix.protocol.api.FrameLayoutSpec
import com.Fusion.Btremix.protocol.api.FrameSegmentRole
import com.Fusion.Btremix.protocol.api.FrameSplitter
import com.Fusion.Btremix.protocol.api.FrameSplitterSpec

/**
 * Splits a stream using a length field from one direction-specific segment table.
 *
 * The length value is the byte count of every segment marked `includeInLength`; fixed bytes outside
 * that span complete the total frame size. Invalid/oversized declarations are abandoned at the next
 * magic, and [reject] lets the transport recover a following frame consumed by a bad length or CRC.
 */
class LengthPrefixedFrameSplitter(
    layout: FrameLayoutSpec,
    private val checksumWidthBytes: Int,
    private val maxFrameBytes: Int = FrameSplitterSpec.DEFAULT_MAX_FRAME_BYTES,
) : FrameSplitter {
    private val magic: ByteArray
    private val lengthIndex: Int
    private val lengthOffset: Int
    private val lengthWidth: Int
    private val lengthOrder: Endianness
    private val fixedBytesOutsideLength: Int
    private val fixedBytesInsideLength: Int

    private var buffer = ByteArray(0)

    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
        require(layout.segments.firstOrNull()?.role == FrameSegmentRole.MAGIC) {
            "Length-prefixed frames must start with a magic segment"
        }
        magic = requireNotNull(layout.segments.first().constant).clone()
        require(magic.isNotEmpty()) { "Magic must not be empty" }
        lengthIndex = layout.segments.indexOfFirst { it.role == FrameSegmentRole.LENGTH }
        require(lengthIndex >= 0) { "Length-prefixed layout requires a length segment" }
        lengthOffset = layout.segments.take(lengthIndex).sumOf {
            requireNotNull(it.width) { "Fixed segment ${it.role} requires width" }
        }
        lengthWidth = requireNotNull(layout.segments[lengthIndex].width)
        lengthOrder = layout.segments[lengthIndex].order
        fixedBytesOutsideLength = layout.segments.sumOf { segment ->
            when {
                segment.includeInLength -> 0
                segment.role == FrameSegmentRole.PAYLOAD -> 0
                else -> segment.width ?: 0
            }
        }
        fixedBytesInsideLength = layout.segments.sumOf { segment ->
            if (segment.includeInLength && segment.role != FrameSegmentRole.PAYLOAD) segment.width ?: 0 else 0
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
                keepPossibleMagicTail()
                return frames
            }
            if (buffer.size - start < lengthOffset + lengthWidth) {
                buffer = buffer.copyOfRange(start, buffer.size)
                return frames
            }
            val length = readLength(start + lengthOffset)
            if (length < fixedBytesInsideLength) {
                searchFrom = start + 1
                continue
            }
            val total = fixedBytesOutsideLength + length
            if (total < lengthOffset + lengthWidth || total > maxFrameBytes) {
                searchFrom = start + 1
                continue
            }
            if (buffer.size - start < total) {
                buffer = buffer.copyOfRange(start, buffer.size)
                return frames
            }
            frames += buffer.copyOfRange(start, start + total)
            searchFrom = start + total
        }
    }

    private fun findMagic(from: Int): Int {
        if (buffer.size < magic.size) return -1
        for (start in from..buffer.size - magic.size) {
            if (magic.indices.all { buffer[start + it] == magic[it] }) return start
        }
        return -1
    }

    private fun keepPossibleMagicTail() {
        val keep = minOf(buffer.size, magic.size - 1)
        buffer = if (keep == 0) ByteArray(0) else buffer.copyOfRange(buffer.size - keep, buffer.size)
    }

    private fun readLength(offset: Int): Int {
        var value = 0
        repeat(lengthWidth) { index ->
            val shift = if (lengthOrder == Endianness.BIG) (lengthWidth - 1 - index) * 8 else index * 8
            value = value or ((buffer[offset + index].toInt() and 0xff) shl shift)
        }
        return value
    }
}

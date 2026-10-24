package com.Fusion.Btremix.protocol.codec.framed

import com.Fusion.Btremix.protocol.api.FrameSplitter
import com.Fusion.Btremix.protocol.api.FrameSplitterSpec

/**
 * Splits a stream on a header/trailer pair.
 *
 * The splitter understands the escape byte only to avoid treating an escaped delimiter as a frame
 * boundary. It does not unescape or decode the returned wire frame. A new unescaped header while a
 * frame is incomplete restarts synchronization, and garbage before a header is discarded.
 */
class DelimitedFrameSplitter(
    private val header: Int,
    private val trailer: Int,
    private val escape: Int? = null,
    private val maxFrameBytes: Int = FrameSplitterSpec.DEFAULT_MAX_FRAME_BYTES,
) : FrameSplitter {
    private var buffer = ByteArray(0)

    init {
        require(header in 0..0xff) { "Frame header must fit UInt8" }
        require(trailer in 0..0xff) { "Frame trailer must fit UInt8" }
        require(header != trailer) { "Frame header and trailer must differ" }
        require(escape == null || escape in 0..0xff) { "Escape byte must fit UInt8" }
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
    }

    override fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        val input = if (buffer.isEmpty()) {
            chunk
        } else {
            ByteArray(buffer.size + chunk.size).also {
                buffer.copyInto(it)
                chunk.copyInto(it, buffer.size)
            }
        }

        val frames = mutableListOf<ByteArray>()
        var frameStart = -1
        var index = 0
        while (index < input.size) {
            val value = input[index].toInt() and 0xff
            if (frameStart < 0) {
                if (value == header) frameStart = index
                index++
                continue
            }

            if (escape != null && value == escape) {
                index += 2
                if (index - frameStart > maxFrameBytes) frameStart = -1
                continue
            }
            if (value == header && index > frameStart) {
                frameStart = index
                index++
                continue
            }
            if (value == trailer) {
                if (index - frameStart + 1 in 1..maxFrameBytes) {
                    frames += input.copyOfRange(frameStart, index + 1)
                }
                frameStart = -1
            } else if (index - frameStart + 1 >= maxFrameBytes) {
                // The oversized candidate cannot become valid. Resume with the next header instead
                // of retaining an unbounded buffer.
                frameStart = -1
            }
            index++
        }

        buffer = if (frameStart >= 0 && input.size - frameStart <= maxFrameBytes) {
            input.copyOfRange(frameStart, input.size)
        } else {
            ByteArray(0)
        }
        return frames
    }

    override fun reset() {
        buffer = ByteArray(0)
    }
}

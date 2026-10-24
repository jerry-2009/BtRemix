package com.Fusion.Btremix.protocol.api

/**
 * Splits a raw byte stream into complete wire frames.
 *
 * Byte-stream transports deliver arbitrary chunks: half a frame, exactly one frame or several
 * frames glued together. Implementations retain only the trailing partial frame.
 */
interface FrameAccumulator {
    /** Returns every complete wire frame contained in [chunk]. */
    fun feed(chunk: ByteArray): List<ByteArray>

    /** Drops bytes buffered from the previous connection or a malformed frame. */
    fun reset()
}

/** Generic stream splitter contract. Kept separate from codec business decoding. */
interface FrameSplitter : FrameAccumulator

/** Configuration selected by `protocol.framing.splitter`. */
data class FrameSplitterSpec(
    val function: String = FrameSplitterRegistry.DELIMITED,
    val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) {
    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
    }

    fun normalized(): FrameSplitterSpec = copy(function = function.trim().lowercase())

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES: Int = 8192
    }
}

fun interface FrameSplitterFactory {
    fun create(framing: FramingSpec): FrameSplitter
}

/** Registry for generic, vendor-neutral stream splitters. */
object FrameSplitterRegistry {
    const val DELIMITED: String = "delimited"

    private val factories = linkedMapOf<String, FrameSplitterFactory>()

    init {
        register(DELIMITED) { framing ->
            com.Fusion.Btremix.protocol.codec.framed.DelimitedFrameSplitter(
                header = framing.header,
                trailer = framing.trailer,
                escape = framing.escape,
                maxFrameBytes = framing.splitter.maxFrameBytes,
            )
        }
    }

    @Synchronized
    fun register(name: String, factory: FrameSplitterFactory) {
        factories[name] = factory
    }

    val names: Set<String>
        @Synchronized get() = factories.keys.toSet()

    @Synchronized
    fun create(name: String, framing: FramingSpec): FrameSplitter? =
        factories[name.trim().lowercase()]?.create(framing)
}

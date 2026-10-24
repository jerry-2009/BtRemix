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

    /**
     * Gives the splitter a chance to recover bytes that were consumed while assembling a candidate
     * frame whose codec validation later failed (CRC, length consistency or any other decode error).
     */
    fun reject(frame: ByteArray): List<ByteArray> = emptyList()
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
    const val LENGTH_PREFIXED: String = "lengthprefixed"

    private val factories = linkedMapOf<String, FrameSplitterFactory>()

    init {
        register(DELIMITED) { framing ->
            com.Fusion.Btremix.protocol.codec.framed.DelimitedFrameSplitter(
                header = requireNotNull(framing.header) { "Delimited splitter requires a header" },
                trailer = requireNotNull(framing.trailer) { "Delimited splitter requires a trailer" },
                escape = framing.escape,
                maxFrameBytes = framing.splitter.maxFrameBytes,
            )
        }
        register(LENGTH_PREFIXED) { framing ->
            val layouts = requireNotNull(framing.segmented) {
                "Length-prefixed splitter requires request/response segment layouts"
            }
            com.Fusion.Btremix.protocol.codec.framed.LengthPrefixedFrameSplitter(
                layout = layouts.response,
                checksumWidthBytes = (framing.checksum?.width ?: 0) / 8,
                maxFrameBytes = framing.splitter.maxFrameBytes,
            )
        }
    }

    @Synchronized
    fun register(name: String, factory: FrameSplitterFactory) {
        factories[name.trim().lowercase()] = factory
    }

    val names: Set<String>
        @Synchronized get() = factories.keys.toSet()

    @Synchronized
    fun create(name: String, framing: FramingSpec): FrameSplitter? =
        factories[name.trim().lowercase()]?.create(framing)
}

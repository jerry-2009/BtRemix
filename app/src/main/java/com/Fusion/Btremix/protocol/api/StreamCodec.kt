package com.Fusion.Btremix.protocol.api

import com.Fusion.Btremix.protocol.codec.framed.FramedStreamCodec
import com.Fusion.Btremix.protocol.codec.framed.SegmentedFrameCodec

/**
 * Builds a [StreamCodec] from the framing a Definition declares.
 *
 * A registry entry is a *generic* implementation (for example "framed"); the vendor-specific part
 * lives entirely in the [FramingSpec] the Definition provides.
 */
fun interface StreamCodecFactory {
    fun create(spec: FramingSpec): StreamCodec
}

/** A packet codec for a byte-stream transport, created from a [FramingSpec]. */
interface StreamCodec : PacketCodec {
    /** Stable name used by `protocol.framing.codec`. */
    val name: String

    /** A fresh accumulator that frames the bytes of one connection. */
    fun accumulator(): FrameAccumulator

    /**
     * Frames this codec wants to send automatically in reply to an inbound packet, in wire order.
     *
     * Some protocols acknowledge every inbound command at the framing level. Expressing it as a
     * codec hook keeps the transport generic: it writes whatever the codec returns before handing
     * the packet to the transaction runtime. Returning an empty list means "no reply".
     */
    fun automaticReplies(packet: Packet): List<Packet> = emptyList()
}

/**
 * Process-wide registry of the generic framing codecs a Definition may name.
 *
 * Only vendor-neutral, config-driven implementations are registered here. An unregistered name is
 * rejected during Definition validation instead of failing later at connection time.
 */
object StreamCodecRegistry {
    private val factories = linkedMapOf<String, StreamCodecFactory>(
        FramedStreamCodec.NAME to StreamCodecFactory { spec -> FramedStreamCodec(spec) },
        SegmentedFrameCodec.NAME to StreamCodecFactory { spec -> SegmentedFrameCodec(spec) },
    )

    @Synchronized
    fun register(name: String, factory: StreamCodecFactory) {
        factories[name] = factory
    }

    @Synchronized
    fun clear() = factories.clear()

    val names: Set<String>
        @Synchronized get() = factories.keys.toSet()

    @Synchronized
    fun create(name: String, spec: FramingSpec): StreamCodec? = factories[name]?.create(spec)
}

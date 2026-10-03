package com.Fusion.Btremix.protocol.api

data class Packet(
    val command: Int,
    val sequence: Int? = null,
    val payload: ByteArray = byteArrayOf(),
) {
    init {
        require(command in 0..0xff) { "Command must fit in UInt8" }
        require(sequence == null || sequence in 0..0xff) { "Sequence must fit in UInt8" }
    }

    fun copyPayload(): ByteArray = payload.clone()

    override fun equals(other: Any?): Boolean = other is Packet &&
        command == other.command && sequence == other.sequence && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * command + (sequence ?: 0)) + payload.contentHashCode()
}

interface PacketEncoder {
    fun encode(packet: Packet): ByteArray
}

interface PacketDecoder {
    fun decode(bytes: ByteArray): Packet
}

/** A codec that can both encode and decode packets. */
interface PacketCodec : PacketEncoder, PacketDecoder

/** Simple wire format: command, optional sequence, then payload. */
class SimplePacketCodec(private val includesSequence: Boolean = true) : PacketCodec {
    override fun encode(packet: Packet): ByteArray {
        if (includesSequence) require(packet.sequence != null) { "Packet sequence is required" }
        val output = ByteArray(1 + (if (includesSequence) 1 else 0) + packet.payload.size)
        output[0] = packet.command.toByte()
        var offset = 1
        if (includesSequence) output[offset++] = packet.sequence!!.toByte()
        packet.payload.copyInto(output, offset)
        return output
    }

    override fun decode(bytes: ByteArray): Packet {
        val headerSize = 1 + (if (includesSequence) 1 else 0)
        require(bytes.size >= headerSize) { "Packet is shorter than its header" }
        var offset = 1
        val sequence = if (includesSequence) bytes[offset++].toInt() and 0xff else null
        return Packet(bytes[0].toInt() and 0xff, sequence, bytes.copyOfRange(offset, bytes.size))
    }
}

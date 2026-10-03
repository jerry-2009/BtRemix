package com.Fusion.Btremix.protocol.codec.framed

import com.Fusion.Btremix.protocol.api.AcknowledgementSequence
import com.Fusion.Btremix.protocol.api.AcknowledgementSpec
import com.Fusion.Btremix.protocol.api.ChecksumAlgorithm
import com.Fusion.Btremix.protocol.api.ChecksumSpec
import com.Fusion.Btremix.protocol.api.Endianness
import com.Fusion.Btremix.protocol.api.FramingLayout
import com.Fusion.Btremix.protocol.api.FramingSpec
import com.Fusion.Btremix.protocol.api.Packet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framing runtime is generic, so the WF-1000XM3 frame layout is exercised as configuration:
 * these are the three test vectors from `HANDOFF_CLASSIC_BT_SPP.md` §2.1.
 */
class FramedStreamCodecTest {
    private val codec = FramedStreamCodec(WF_1000_XM3_FRAMING)

    @Test
    fun encodesGetProtocolInfoVector() {
        val frame = codec.encode(Packet(command = 0x0C, sequence = 0, payload = byteArrayOf(0x00, 0x00)))

        assertArrayEquals(hex("3E 0C 00 00 00 00 02 00 00 0E 3C"), frame)
    }

    @Test
    fun escapesPayloadBytesThatCollideWithTheDelimiters() {
        val headerInPayload = codec.encode(Packet(command = 0x0C, sequence = 1, payload = byteArrayOf(0x3E)))
        assertArrayEquals(hex("3E 0C 01 00 00 00 01 3D 2E 4C 3C"), headerInPayload)

        val escapeAndTrailerInPayload = codec.encode(Packet(command = 0x0C, sequence = 0, payload = byteArrayOf(0x3C, 0x3D)))
        assertArrayEquals(hex("3E 0C 00 00 00 00 02 3D 2C 3D 2D 87 3C"), escapeAndTrailerInPayload)
    }

    @Test
    fun decodeRoundTripsEveryVector() {
        listOf(
            Packet(0x0C, 0, byteArrayOf(0x00, 0x00)),
            Packet(0x0C, 1, byteArrayOf(0x3E)),
            Packet(0x0C, 0, byteArrayOf(0x3C, 0x3D)),
            Packet(0x0E, 1, byteArrayOf(0x3D, 0x3D, 0x3E, 0x3C)),
        ).forEach { packet ->
            assertEquals(packet, codec.decode(codec.encode(packet)))
        }
    }

    @Test
    fun rejectsCorruptedChecksumTruncatedFrameAndMissingDelimiter() {
        val frame = codec.encode(Packet(0x0C, 1, byteArrayOf(0x3E)))
        val corrupted = frame.clone().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() }

        assertTrue(runCatching { codec.decode(corrupted) }.isFailure)
        assertTrue(runCatching { codec.decode(frame.copyOf(frame.size - 1)) }.isFailure)
        assertTrue(runCatching { codec.decode(byteArrayOf(0x00) + frame.copyOfRange(1, frame.size)) }.isFailure)
    }

    @Test
    fun acknowledgementSpecDrivesTheReplySequence() {
        assertEquals(Packet(0x01, 0), codec.automaticReplies(Packet(0x0C, 1, byteArrayOf(0x22))).single())
        assertEquals(Packet(0x01, 1), codec.automaticReplies(Packet(0x0E, 0, byteArrayOf(0x06))).single())
        assertTrue(codec.automaticReplies(Packet(0x01, 0)).isEmpty())
        assertTrue(codec.automaticReplies(Packet(0x22, 0, byteArrayOf(0x01))).isEmpty())
    }

    @Test
    fun accumulatorHandlesPartialAndCoalescedFrames() {
        val accumulator = codec.accumulator()
        val first = codec.encode(Packet(0x0C, 0, byteArrayOf(0x00, 0x00)))
        val second = codec.encode(Packet(0x0C, 1, byteArrayOf(0x3E)))

        assertTrue(accumulator.feed(first.copyOfRange(0, 4)).isEmpty())
        val firstFrames = accumulator.feed(first.copyOfRange(4, first.size) + second)

        assertEquals(2, firstFrames.size)
        assertArrayEquals(first, firstFrames[0])
        assertArrayEquals(second, firstFrames[1])
    }

    private fun hex(text: String): ByteArray =
        text.split(" ").filter(String::isNotBlank).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        /**
         * Exactly the WF-1000XM3 framing from `SONY_WF1000XM3_PROTOCOL.md` §2, expressed as data.
         */
        val WF_1000_XM3_FRAMING = FramingSpec(
            header = 0x3E,
            trailer = 0x3C,
            escape = 0x3D,
            escapeMask = 0xEF,
            checksum = ChecksumSpec(ChecksumAlgorithm.SUM8),
            layout = FramingLayout(
                messageTypeOffset = 0,
                sequenceOffset = 1,
                lengthOffset = 2,
                lengthBytes = 4,
                lengthByteOrder = Endianness.BIG,
            ),
            acknowledgement = AcknowledgementSpec(
                messageTypes = setOf(0x0C, 0x0E),
                replyMessageType = 0x01,
                sequence = AcknowledgementSequence.COMPLEMENT,
            ),
        )
    }
}

package com.fusion.melodyLinkNeo.protocol

import com.fusion.melodyLinkNeo.protocol.api.AcknowledgementSequence
import com.fusion.melodyLinkNeo.protocol.api.AcknowledgementSpec
import com.fusion.melodyLinkNeo.protocol.api.ChecksumAlgorithm
import com.fusion.melodyLinkNeo.protocol.api.ChecksumSpec
import com.fusion.melodyLinkNeo.protocol.api.Endianness
import com.fusion.melodyLinkNeo.protocol.api.FramingLayout
import com.fusion.melodyLinkNeo.protocol.api.FramingSpec
import com.fusion.melodyLinkNeo.protocol.api.Packet
import com.fusion.melodyLinkNeo.protocol.codec.framed.FramedStreamCodec
import com.fusion.melodyLinkNeo.protocol.stream.StreamTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the generic stream transport: framing an inbound frame and replying when the codec asks. */
class StreamTransportTest {
    private val codec = FramedStreamCodec(FRAMING)

    @Test
    fun inboundCommand_isEmittedAndAcknowledgedWithComplementedSequence() = runBlocking {
        val stream = FakeByteStream()
        val transport = StreamTransport(stream, codec, Dispatchers.Unconfined)
        val received = mutableListOf<Packet>()
        val job = launch(Dispatchers.Unconfined) { transport.packets().collect { received += it } }
        stream.awaitSubscriber()

        stream.emit(codec.encode(Packet(0x0C, 1, byteArrayOf(0x22, 0x01))))
        yield()

        assertEquals(Packet(0x0C, 1, byteArrayOf(0x22, 0x01)), received.single())
        assertArrayEquals(codec.encode(Packet(0x01, 0)), stream.writes.single())
        job.cancel()
        transport.close()
    }

    @Test
    fun framesThatAreNotCommandsAreNotAcknowledged() = runBlocking {
        val stream = FakeByteStream()
        val transport = StreamTransport(stream, codec, Dispatchers.Unconfined)
        val received = mutableListOf<Packet>()
        val job = launch(Dispatchers.Unconfined) { transport.packets().collect { received += it } }
        stream.awaitSubscriber()

        stream.emit(codec.encode(Packet(0x01, 0)))
        yield()

        assertEquals(Packet(0x01, 0), received.single())
        assertTrue("an ACK frame must never be acknowledged", stream.writes.isEmpty())
        job.cancel()
        transport.close()
    }

    @Test
    fun notifications_areTheRawFramesSoTheTransactionRuntimeCanDecodeThem() = runBlocking {
        val stream = FakeByteStream()
        val transport = StreamTransport(stream, codec, Dispatchers.Unconfined)
        val frames = mutableListOf<ByteArray>()
        val job = launch(Dispatchers.Unconfined) { transport.notifications().collect { frames += it } }
        stream.awaitSubscriber()

        val frame = codec.encode(Packet(0x0C, 0, byteArrayOf(0x25, 0x01, 0x50, 0x00)))
        stream.emit(frame)
        yield()

        assertArrayEquals(frame, frames.single())
        job.cancel()
        transport.close()
    }

    private companion object {
        val FRAMING = FramingSpec(
            header = 0x3E,
            trailer = 0x3C,
            escape = 0x3D,
            escapeMask = 0xEF,
            checksum = ChecksumSpec(ChecksumAlgorithm.SUM8),
            layout = FramingLayout(0, 1, 2, 4, Endianness.BIG),
            acknowledgement = AcknowledgementSpec(setOf(0x0C, 0x0E), 0x01, sequence = AcknowledgementSequence.COMPLEMENT),
        )
    }
}

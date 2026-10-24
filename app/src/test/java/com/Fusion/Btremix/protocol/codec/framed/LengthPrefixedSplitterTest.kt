package com.Fusion.Btremix.protocol.codec.framed

import com.Fusion.Btremix.protocol.FakeByteStream
import com.Fusion.Btremix.protocol.stream.StreamTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LengthPrefixedSplitterTest {
    private val framing = SegmentedFrameCodecTest.framing()
    private val codec = SegmentedFrameCodec(framing)
    private val request = codec.encodeRequest(com.Fusion.Btremix.protocol.api.Packet(0x1003))
    private val response = codec.encodeResponse(
        com.Fusion.Btremix.protocol.api.Packet(0x9003, payload = byteArrayOf(0x02)),
    )

    @Test
    fun everySingleAndDoubleChunkBoundaryProducesTheSameFrame() {
        (0..response.size).forEach { first ->
            val splitter = codec.accumulator()
            val frames = splitter.feed(response.copyOfRange(0, first)) +
                splitter.feed(response.copyOfRange(first, response.size))
            assertEquals("single split at $first", 1, frames.size)
            assertArrayEquals(response, frames.single())
        }

        (0..response.size).forEach { first ->
            (first..response.size).forEach { second ->
                val splitter = codec.accumulator()
                val frames = splitter.feed(response.copyOfRange(0, first)) +
                    splitter.feed(response.copyOfRange(first, second)) +
                    splitter.feed(response.copyOfRange(second, response.size))
                assertEquals("double split at $first/$second", 1, frames.size)
                assertArrayEquals(response, frames.single())
            }
        }
    }

    @Test
    fun requestLayoutHonoursTheSameArbitraryBoundaryContract() {
        val layout = requireNotNull(framing.segmented).request
        (0..request.size).forEach { first ->
            val splitter = LengthPrefixedFrameSplitter(layout, checksumWidthBytes = 2, maxFrameBytes = 4096)
            val frames = splitter.feed(request.copyOfRange(0, first)) +
                splitter.feed(request.copyOfRange(first, request.size))
            assertEquals("request split at $first", 1, frames.size)
            assertArrayEquals(request, frames.single())
        }
        (0..request.size).forEach { first ->
            (first..request.size).forEach { second ->
                val splitter = LengthPrefixedFrameSplitter(layout, checksumWidthBytes = 2, maxFrameBytes = 4096)
                val frames = splitter.feed(request.copyOfRange(0, first)) +
                    splitter.feed(request.copyOfRange(first, second)) +
                    splitter.feed(request.copyOfRange(second, request.size))
                assertEquals("request double split at $first/$second", 1, frames.size)
                assertArrayEquals(request, frames.single())
            }
        }
    }

    @Test
    fun oneByteChunksCoalescedFramesNoiseAndResetAreBounded() {
        val splitter = codec.accumulator()
        val output = mutableListOf<ByteArray>()
        (byteArrayOf(0x00, 0xFF.toByte(), 0x01) + response + response).forEach { byte ->
            output += splitter.feed(byteArrayOf(byte))
        }
        assertEquals(2, output.size)
        assertArrayEquals(response, output[0])
        assertArrayEquals(response, output[1])

        splitter.feed(response.copyOfRange(0, 4))
        splitter.reset()
        assertArrayEquals(response, splitter.feed(response).single())
    }

    @Test
    fun oversizedLengthResynchronisesAtTheNextMagic() {
        val splitter = codec.accumulator()
        val oversize = byteArrayOf(
            0xA5.toByte(), 0x5A, 0x00, 0x90.toByte(), 0x03, 0xFF.toByte(), 0xFF.toByte(),
        )

        val frames = splitter.feed(oversize + response + response)

        assertEquals(2, frames.size)
        assertArrayEquals(response, frames[0])
        assertArrayEquals(response, frames[1])
    }

    @Test
    fun badChecksumIsDroppedAndTheFollowingFrameStillArrives() = runBlocking {
        val bad = response.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val stream = FakeByteStream()
        val transport = StreamTransport(stream, codec, Dispatchers.Unconfined)
        val packets = mutableListOf<com.Fusion.Btremix.protocol.api.Packet>()
        val job = launch(Dispatchers.Unconfined) { transport.packets().collect(packets::add) }
        stream.awaitSubscriber()

        stream.emit(bad + response)
        delay(20)
        yield()

        assertEquals(1, transport.badFrameCount)
        assertEquals(1, packets.size)
        assertEquals(0x9003, packets.single().command)
        job.cancel()
        transport.close()
    }

    @Test
    fun badLengthRecoversAFrameWhoseMagicWasSwallowedByTheCandidate() = runBlocking {
        val badLength = response.clone().also {
            it[5] = 0x04
            it[6] = 0x00
        }
        val stream = FakeByteStream()
        val transport = StreamTransport(stream, codec, Dispatchers.Unconfined)
        val packets = mutableListOf<com.Fusion.Btremix.protocol.api.Packet>()
        val job = launch(Dispatchers.Unconfined) { transport.packets().collect(packets::add) }
        stream.awaitSubscriber()

        // The first splitter candidate consumes the next frame's first byte; reject() must recover it.
        stream.emit(badLength.copyOf(7))
        stream.emit(badLength.copyOfRange(7, badLength.size) + response.copyOfRange(0, 1))
        stream.emit(response.copyOfRange(1, response.size))
        delay(30)
        yield()

        assertEquals(1, transport.badFrameCount)
        assertEquals(0x9003, packets.single().command)
        job.cancel()
        transport.close()
    }
}

package com.Fusion.Btremix.protocol.codec.framed

import com.Fusion.Btremix.protocol.FakeByteStream
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.stream.StreamTransport
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSplitterFuzzTest {
    private val codec = SegmentedFrameCodec(SegmentedFrameCodecTest.framing())

    @Test
    fun randomChunkBoundariesAndNoisePreserveEveryCompleteFrame() {
        val random = Random(7)
        val frames = (0 until 40).map { index ->
            codec.encodeResponse(Packet(0x9003, payload = byteArrayOf(index.toByte(), 0x7F)))
        }
        val wire = mutableListOf<Byte>()
        frames.forEach { frame ->
            repeat(random.nextInt(0, 5)) { wire += byteArrayOf(0x00, 0x11, 0x22)[random.nextInt(3)] }
            wire += frame.toList()
        }
        val splitter = codec.accumulator()
        val output = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < wire.size) {
            val end = minOf(wire.size, offset + random.nextInt(1, 18))
            output += splitter.feed(wire.subList(offset, end).toByteArray())
            offset = end
        }

        assertEquals(frames.size, output.size)
        frames.forEachIndexed { index, frame -> assertArrayEquals(frame, output[index]) }
    }

    @Test
    fun longNoiseAndConsecutiveBadChecksumsStayBoundedAndRecover() = runBlocking {
        val splitter = codec.accumulator()
        val good = codec.encodeResponse(Packet(0x9003, payload = byteArrayOf(0x02)))
        assertArrayEquals(good, splitter.feed(ByteArray(100_000) { 0x11 } + good).single())

        val bad = good.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val stream = FakeByteStream()
        val transport = StreamTransport(stream, codec, Dispatchers.Unconfined)
        val received = mutableListOf<Packet>()
        val job = launch(Dispatchers.Unconfined) { transport.packets().collect(received::add) }
        stream.awaitSubscriber()
        repeat(64) { stream.emit(bad) }
        stream.emit(good)
        delay(30)
        yield()

        assertEquals(64, transport.badFrameCount)
        assertEquals(listOf(Packet(0x9003, payload = byteArrayOf(0x02))), received)
        job.cancel()
        transport.close()
    }
}

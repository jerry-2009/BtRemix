package com.Fusion.Btremix.protocol.codec.framed

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DelimitedFrameSplitterTest {
    @Test
    fun arbitrarySingleByteChunksPreserveHeaderBodyAndTrailer() {
        val splitter = DelimitedFrameSplitter(0xA5, 0x5A, escape = 0x7D)
        val frame = byteArrayOf(0xA5.toByte(), 0x01, 0x7D, 0x25, 0x02, 0x5A)
        val output = mutableListOf<ByteArray>()

        frame.forEach { output += splitter.feed(byteArrayOf(it)) }

        assertEquals(1, output.size)
        assertArrayEquals(frame, output.single())
    }

    @Test
    fun escapesDelimitersAndHandlesCoalescedFramesAndLeadingNoise() {
        val splitter = DelimitedFrameSplitter(0xA5, 0x5A, escape = 0x7D)
        val first = byteArrayOf(0xA5.toByte(), 0x7D, 0x25, 0x5A)
        val second = byteArrayOf(0xA5.toByte(), 0x01, 0x5A)

        val frames = splitter.feed(byteArrayOf(0x00, 0xFF.toByte()) + first + second)

        assertEquals(2, frames.size)
        assertArrayEquals(first, frames[0])
        assertArrayEquals(second, frames[1])
    }

    @Test
    fun incompleteOrCorruptFrameResynchronisesAtTheNextHeader() {
        val splitter = DelimitedFrameSplitter(0xA5, 0x5A, maxFrameBytes = 8)
        val valid = byteArrayOf(0xA5.toByte(), 0x01, 0x5A)
        val malformed = byteArrayOf(0xA5.toByte(), 0x01, 0x02, 0x03, 0x04)

        val frames = splitter.feed(malformed + valid)

        assertEquals(1, frames.size)
        assertArrayEquals(valid, frames.single())
    }

    @Test
    fun frameAtMaximumIsAcceptedAndOversizeIsDroppedBeforeFollowingFrame() {
        val exact = byteArrayOf(0xA5.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x5A)
        val splitter = DelimitedFrameSplitter(0xA5, 0x5A, maxFrameBytes = exact.size)
        assertArrayEquals(exact, splitter.feed(exact).single())

        val oversized = ByteArray(9) { 0x11 } .also {
            it[0] = 0xA5.toByte()
            it[it.lastIndex] = 0x5A
        }
        val follower = byteArrayOf(0xA5.toByte(), 0x02, 0x5A)
        val frames = splitter.feed(oversized + follower)
        assertEquals(1, frames.size)
        assertArrayEquals(follower, frames.single())
    }

    @Test
    fun commonAndCustomMaximumLimitsRemainBounded() {
        listOf(4096, 8192, 257).forEach { maximum ->
            val splitter = DelimitedFrameSplitter(0xA5, 0x5A, maxFrameBytes = maximum)
            val frame = ByteArray(maximum).also {
                it[0] = 0xA5.toByte()
                it[it.lastIndex] = 0x5A
            }
            assertEquals(maximum, splitter.feed(frame).single().size)
        }
    }

    @Test
    fun resetNeverJoinsOldConnectionBytesToTheNewConnection() {
        val splitter = DelimitedFrameSplitter(0xA5, 0x5A)
        assertTrue(splitter.feed(byteArrayOf(0xA5.toByte(), 0x01)).isEmpty())

        splitter.reset()
        val fresh = byteArrayOf(0xA5.toByte(), 0x02, 0x5A)

        assertArrayEquals(fresh, splitter.feed(fresh).single())
    }
}

package com.Fusion.Btremix.protocol.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketMatcherSpecTest {
    @Test
    fun commands_acceptOnlyTheDeclaredCommandSet() {
        val matcher = PacketMatcherSpec(
            commands = setOf(0x02, 0x0E),
            payloadPrefix = byteArrayOf(0x04),
        )

        assertTrue(matcher.matches(Packet(0x02, 0x10, byteArrayOf(0x04, 0x07)), null))
        assertTrue(matcher.matches(Packet(0x0E, 0x11, byteArrayOf(0x04, 0x00)), null))
        assertFalse(matcher.matches(Packet(0x08, 0x12, byteArrayOf(0x04, 0x02)), null))
        assertFalse(matcher.matches(Packet(0x02, 0x13, byteArrayOf(0x02, 0x04)), null))
    }
}

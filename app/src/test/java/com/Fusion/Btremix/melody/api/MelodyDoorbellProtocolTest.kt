package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the doorbell wire contract (MELODY_BRIDGE_TRANSPORT_PLAN.md §4, §11.2).
 *
 * The two halves of the handshake live in different processes and cannot be compiled against each other
 * changes, so the action string, the extras keys and the accept/cadence rules are asserted here: a silent
 * rename would otherwise only show up as "the panel never connects" on a real device.
 */
class MelodyDoorbellProtocolTest {

    @Test
    fun wireConstants_areStableAcrossProcesses() {
        assertEquals("com.Fusion.Btremix.melody.BRIDGE_DOORBELL", MelodyDoorbellProtocol.ACTION)
        assertEquals(1, MelodyDoorbellProtocol.VERSION)
        assertEquals("bridge", MelodyDoorbellProtocol.EXTRA_BRIDGE)
        assertEquals("generation", MelodyDoorbellProtocol.EXTRA_GENERATION)
        assertEquals("protocol", MelodyDoorbellProtocol.EXTRA_PROTOCOL)
        assertEquals("sender", MelodyDoorbellProtocol.EXTRA_SENDER)
        assertEquals(30_000L, MelodyDoorbellProtocol.KEEPALIVE_MS)
    }

    @Test
    fun accepts_dropsOnlyARepeatedGenerationWhileTheLinkIsAlive() {
        // Same binder, already registered: the keepalive must be idempotent.
        assertFalse(MelodyDoorbellProtocol.accepts(generation = 42, currentGeneration = 42, hasLiveProxy = true))
        // Same generation without a live proxy: the link died, so take it.
        assertTrue(MelodyDoorbellProtocol.accepts(generation = 42, currentGeneration = 42, hasLiveProxy = false))
        // A new binder always replaces the old one.
        assertTrue(MelodyDoorbellProtocol.accepts(generation = 43, currentGeneration = 42, hasLiveProxy = true))
        // Nothing has ever been attached.
        assertTrue(MelodyDoorbellProtocol.accepts(generation = 7, currentGeneration = 0, hasLiveProxy = false))
    }

    @Test
    fun isCompatible_acceptsOwnVersionAndRejectsUnknownOnes() {
        assertTrue(MelodyDoorbellProtocol.isCompatible(MelodyDoorbellProtocol.VERSION))
        assertTrue(MelodyDoorbellProtocol.isCompatible(1))
        assertFalse("a missing extra must not be treated as version 0 of the protocol", MelodyDoorbellProtocol.isCompatible(0))
        assertFalse(MelodyDoorbellProtocol.isCompatible(-1))
        assertFalse(MelodyDoorbellProtocol.isCompatible(MelodyDoorbellProtocol.VERSION + 1))
    }

    @Test
    fun nextDelayMs_burstsUntilAClientAttachesThenKeepsAlive() {
        assertEquals(0L, MelodyDoorbellProtocol.nextDelayMs(attempt = 1, clientAttached = false))
        assertEquals(2_000L, MelodyDoorbellProtocol.nextDelayMs(attempt = 2, clientAttached = false))
        assertEquals(5_000L, MelodyDoorbellProtocol.nextDelayMs(attempt = 3, clientAttached = false))
        assertEquals(10_000L, MelodyDoorbellProtocol.nextDelayMs(attempt = 4, clientAttached = false))
        assertEquals(30_000L, MelodyDoorbellProtocol.nextDelayMs(attempt = 5, clientAttached = false))
        // The cap holds for every later attempt: the sender must never drop out of the loop.
        assertEquals(30_000L, MelodyDoorbellProtocol.nextDelayMs(attempt = 99, clientAttached = false))
        // Defensive: an attempt counter that was not incremented yet still yields the immediate slot.
        assertEquals(0L, MelodyDoorbellProtocol.nextDelayMs(attempt = 0, clientAttached = false))
    }

    @Test
    fun nextDelayMs_keepsTheFullKeepaliveOnceAClientIsAttached() {
        assertEquals(MelodyDoorbellProtocol.KEEPALIVE_MS, MelodyDoorbellProtocol.nextDelayMs(1, clientAttached = true))
        assertEquals(MelodyDoorbellProtocol.KEEPALIVE_MS, MelodyDoorbellProtocol.nextDelayMs(4, clientAttached = true))
    }
}

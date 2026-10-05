package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.4 acceptance for the snapshot fan-out that drives the panel's consistency refresh: adding is
 * idempotent, removing stops delivery, and one misbehaving listener never breaks the push path.
 */
class MelodySnapshotListenersTest {

    @Test
    fun notify_reachesEveryListener() {
        val listeners = MelodySnapshotListeners()
        val seen = mutableListOf<String>()
        listeners.add { mac -> seen += "a:$mac" }
        listeners.add { mac -> seen += "b:$mac" }

        listeners.notifySnapshot("AA:BB")

        assertEquals(listOf("a:AA:BB", "b:AA:BB"), seen)
    }

    @Test
    fun add_isIdempotentAndRemoveStopsDelivery() {
        val listeners = MelodySnapshotListeners()
        var count = 0
        val listener = MelodySnapshotListeners.Listener { count++ }
        listeners.add(listener)
        listeners.add(listener)

        assertEquals(1, listeners.size())
        listeners.notifySnapshot("AA")
        assertEquals(1, count)

        listeners.remove(listener)
        listeners.notifySnapshot("AA")
        assertEquals(1, count)
        assertEquals(0, listeners.size())
    }

    @Test
    fun aThrowingListener_doesNotStopTheOthers() {
        val listeners = MelodySnapshotListeners()
        var reached = false
        listeners.add { throw IllegalStateException("boom") }
        listeners.add { reached = true }

        listeners.notifySnapshot("AA")

        assertTrue(reached)
    }
}

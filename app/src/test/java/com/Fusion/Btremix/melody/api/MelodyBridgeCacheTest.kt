package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cold-start bookkeeping on the Melody side (MELODY_BRIDGE_SPEC §5.5).
 *
 * The provider can be queried before BtRemix is reachable, so "what did we last see, and how old is
 * it" has to be answerable without the service. A controllable clock makes the staleness boundary and
 * the change detection deterministic.
 */
class MelodyBridgeCacheTest {

    private var now = 1_000L
    private val cache = MelodyBridgeCache { now }
    private val mac = "aa:bb:cc:dd:ee:ff"

    @Test
    fun noteManagedMacs_normalisesAndReportsChanges() {
        assertTrue(cache.noteManagedMacs(listOf(" aa:bb:cc:dd:ee:ff ")))
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), cache.managedMacs())

        assertFalse(cache.noteManagedMacs(listOf("AA:BB:CC:DD:EE:FF")))
        assertTrue(cache.noteManagedMacs(emptyList()))
    }

    @Test
    fun recordSnapshot_isLookupableByAnyMacShape() {
        cache.recordSnapshot(mac, "Ready", listOf("anc.mode", "battery.left"))

        val entry = cache.snapshot("AA:BB:CC:DD:EE:FF")
        assertEquals("Ready", entry?.lifecycle)
        assertEquals(listOf("anc.mode", "battery.left"), entry?.stateKeys)
        assertEquals(1_000L, entry?.updatedAtMs)
    }

    @Test
    fun lastSnapshotWins() {
        cache.recordSnapshot(mac, "Ready", listOf("a"))
        now = 2_000L
        cache.recordSnapshot(mac, "Disconnected", emptyList())

        assertEquals("Disconnected", cache.snapshot(mac)?.lifecycle)
        assertEquals(2_000L, cache.snapshot(mac)?.updatedAtMs)
    }

    @Test
    fun recordSnapshot_keepsTheDecodedBatteryLevels() {
        cache.recordSnapshot(
            mac,
            "Ready",
            listOf("battery.left", "battery.case"),
            MelodyEarphoneBattery(left = 80, box = 55),
        )

        val entry = cache.snapshot(mac)
        assertEquals(80, entry?.battery?.left)
        assertEquals(55, entry?.battery?.box)
        assertNull(entry?.battery?.right)
    }

    @Test
    fun aLaterSnapshotWithoutBattery_clearsTheLevels() {
        cache.recordSnapshot(mac, "Ready", listOf("battery.left"), MelodyEarphoneBattery(left = 80))
        cache.recordSnapshot(mac, "Disconnected", emptyList())

        assertNull(cache.snapshot(mac)?.battery)
    }

    @Test
    fun isSnapshotStale_usesTheInjectedClock() {
        cache.recordSnapshot(mac, "Ready", emptyList())
        assertFalse(cache.isSnapshotStale(mac, maxAgeMs = 5_000L))

        now = 6_500L
        assertTrue(cache.isSnapshotStale(mac, maxAgeMs = 5_000L))
    }

    @Test
    fun unknownMac_isTreatedAsStaleAndAbsent() {
        assertTrue(cache.isSnapshotStale(mac, maxAgeMs = 1L))
        assertNull(cache.snapshot(mac))
        assertNull(cache.support(mac))
    }

    @Test
    fun recordSupport_keepsIdentityForProviderFallback() {
        val entry = cache.recordSupport(mac, managed = true, name = "Sony WF-1000XM3", productId = "0x0102")

        assertEquals("Sony WF-1000XM3", entry.name)
        assertEquals("0x0102", cache.support(mac)?.productId)
        assertTrue(cache.support(mac)?.managed == true)
    }

    @Test
    fun forget_dropsBothViews() {
        cache.recordSnapshot(mac, "Ready", listOf("a"))
        cache.recordSupport(mac, managed = true)

        cache.forget(mac)

        assertNull(cache.snapshot(mac))
        assertNull(cache.support(mac))
    }
}

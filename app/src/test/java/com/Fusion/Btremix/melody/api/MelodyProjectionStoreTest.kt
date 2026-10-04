package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.3 acceptance for the cold-start cache (MELODY_BRIDGE_SPEC §5.5): the host must be able to answer
 * the provider with the last known projections after BtRemix is killed, must refuse a document written
 * by another envelope version, and must know how old what it is serving is.
 */
class MelodyProjectionStoreTest {

    private var now = 10_000L
    private val store = MelodyProjectionStore { now }
    private val mac = "14:3f:a6:02:5f:b0"
    private val normalized = "14:3F:A6:02:5F:B0"

    @Test
    fun putAndRoundTripThroughThePersistedDocument() {
        store.put(mac, ENVELOPE, version = 1)

        val restored = MelodyProjectionStore { now }
        assertTrue(restored.load(store.toJson(), expectedVersion = 1))

        assertEquals(listOf(normalized), restored.managedMacs())
        assertEquals(ENVELOPE, restored.envelope(mac))
        assertEquals(10_000L, restored.updatedAtMs())
    }

    @Test
    fun noteManagedMacsReplacesTheSetAndDropsStaleEnvelopes() {
        store.put("AA:BB:CC:DD:EE:01", ENVELOPE, version = 1)
        store.put("AA:BB:CC:DD:EE:02", ENVELOPE, version = 1)

        store.noteManagedMacs(listOf("AA:BB:CC:DD:EE:02"), version = 1)

        assertEquals(listOf("AA:BB:CC:DD:EE:02"), store.managedMacs())
        assertNull(store.envelope("AA:BB:CC:DD:EE:01"))
        assertEquals(ENVELOPE, store.envelope("AA:BB:CC:DD:EE:02"))
    }

    @Test
    fun loadRefusesADocumentFromAnotherEnvelopeVersion() {
        store.put(mac, ENVELOPE, version = 1)
        val document = store.toJson()

        val restored = MelodyProjectionStore { now }
        assertFalse(restored.load(document, expectedVersion = 2))
        assertTrue(restored.isEmpty())
    }

    @Test
    fun loadRefusesCorruptOrAbsentDocuments() {
        assertFalse(store.load(null, expectedVersion = 1))
        assertFalse(store.load("", expectedVersion = 1))
        assertFalse(store.load("{ not json", expectedVersion = 1))
        assertFalse(store.load("""{"macs":[]}""", expectedVersion = 1))
    }

    @Test
    fun stalenessUsesTheInjectedClock() {
        store.put(mac, ENVELOPE, version = 1)
        assertFalse(store.isStale(maxAgeMs = 5_000L))

        now = 16_000L
        assertTrue(store.isStale(maxAgeMs = 5_000L))
        assertEquals(6_000L, store.ageMs())
    }

    @Test
    fun anEmptyStoreIsAlwaysStale() {
        assertTrue(store.isStale(maxAgeMs = 1L))
        assertEquals(-1L, store.ageMs())
    }

    @Test
    fun clearDropsEverything() {
        store.put(mac, ENVELOPE, version = 1)

        store.clear()

        assertTrue(store.isEmpty())
        assertEquals(0, store.version())
        assertNull(store.envelope(mac))
    }

    private companion object {
        const val ENVELOPE =
            """{"version":1,"mac":"14:3F:A6:02:5F:B0","whitelist":{"id":"000CE0","name":"Sony WF-1000XM3"}}"""
    }
}

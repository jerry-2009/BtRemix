package com.Fusion.Btremix.melody.bridge

import com.Fusion.Btremix.melody.api.MelodyDiagnosticPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M5.4 D-28: the export ring buffer (bounded, ordered, plain `evt=` lines). */
class MelodyDiagnosticStoreTest {

    @Before
    fun reset() {
        MelodyDiagnosticStore.clear()
    }

    @After
    fun tearDown() {
        MelodyDiagnosticStore.clear()
    }

    @Test
    fun diagnosticsDefaultsOff() {
        // M5.4 D-29 revised (2026-10-05 review): collecting is opt-in; the M5.5 matrix flips it on.
        assertEquals(false, MelodyDiagnosticStore.diagnosticsEnabled)
    }

    @Test
    fun recordsEventsInOrderWithTheSharedLineFormat() {
        MelodyDiagnosticStore.record("melody.redirect.anc", listOf("mac" to "AA:BB", "index" to 3))
        MelodyDiagnosticStore.record("melody.host.version_unsupported", listOf("range" to ">=18"))

        val events = MelodyDiagnosticStore.snapshot()

        assertEquals(2, events.size)
        assertEquals("evt=melody.redirect.anc mac=AA:BB index=3", events[0])
        // `MelodyEventFormat` quotes values that contain `=`, so a range reads back verbatim.
        assertEquals("evt=melody.host.version_unsupported range=\">=18\"", events[1])
    }

    @Test
    fun ringBufferDropsOldestAndCountsTheLoss() {
        repeat(MelodyDiagnosticPolicy.MAX_EVENTS + 5) { index ->
            MelodyDiagnosticStore.record("melody.anchor.hit", listOf("n" to index))
        }

        assertEquals(MelodyDiagnosticPolicy.MAX_EVENTS, MelodyDiagnosticStore.size())
        assertEquals(5, MelodyDiagnosticStore.dropped())
        assertTrue(MelodyDiagnosticStore.snapshot().first().contains("n=5"))
    }

    @Test
    fun renderCarriesTheHeaderAndEveryLine() {
        MelodyDiagnosticStore.record("melody.anchor.missing", listOf("hook" to "redirect.v0"))

        val text = MelodyDiagnosticStore.render(
            linkedMapOf("hostVersion" to "17.6.3", "diagnosticsEnabled" to "true"),
        )

        assertTrue(text.startsWith("# BtRemix Melody diagnostics"))
        assertTrue(text.contains("# hostVersion: 17.6.3"))
        assertTrue(text.contains("# events: 1"))
        assertTrue(text.contains("evt=melody.anchor.missing hook=redirect.v0"))
    }

    @Test
    fun clearResetsBothTheBufferAndTheDropCounter() {
        repeat(MelodyDiagnosticPolicy.MAX_EVENTS + 2) { MelodyDiagnosticStore.record("melody.anchor.hit", emptyList()) }

        MelodyDiagnosticStore.clear()

        assertEquals(0, MelodyDiagnosticStore.size())
        assertEquals(0, MelodyDiagnosticStore.dropped())
    }
}

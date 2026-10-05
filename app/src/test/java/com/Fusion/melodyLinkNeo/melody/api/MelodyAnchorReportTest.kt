package com.fusion.melodyLinkNeo.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M6 anchor report codec + merge semantics (persisted by BtRemix, read back by the host). */
class MelodyAnchorReportTest {

    private fun process(name: String = "com.oplus.melody") = MelodyAnchorProcessReport(
        processName = name,
        anchors = listOf(
            MelodyAnchorEntry("redirect.v0", "com.oplus.melody.model.repository.earphone.J", "v0", MelodyAnchorLevel.Baseline),
            MelodyAnchorEntry("card.menu_builder", "i9.c", "e", MelodyAnchorLevel.Dexkit),
            MelodyAnchorEntry("panel.group_observer", null, null, MelodyAnchorLevel.Missing),
        ),
    )

    @Test
    fun roundTrip_preservesEveryField() {
        val report = MelodyAnchorReport(
            installId = "abc123",
            hostPackage = "com.oplus.melody",
            version = "17.7.0",
            processes = listOf(process()),
        )

        val decoded = MelodyAnchorReport.decode(MelodyAnchorReport.encode(report))

        assertEquals(report, decoded)
    }

    @Test
    fun blankOrMalformedText_decodesToNull() {
        assertNull(MelodyAnchorReport.decode(null))
        assertNull(MelodyAnchorReport.decode(""))
        assertNull(MelodyAnchorReport.decode("not json"))
        assertNull(MelodyAnchorReport.decode("""{"installId":"x"}"""))
    }

    @Test
    fun missingAnchor_hasNoClassAndCountsAsMiss() {
        val report = process()

        assertEquals(2, report.hits)
        assertEquals(3, report.total)
        assertEquals(listOf("panel.group_observer"), report.misses.map { it.id })
        assertTrue(report.anchors.first { it.id == "panel.group_observer" }.missing)
    }

    @Test
    fun merge_sameInstall_replacesOnlyThatProcess() {
        val first = MelodyAnchorReport.merge(
            null,
            installId = "abc",
            hostPackage = "com.oplus.melody",
            version = "17.6.3",
            processReport = process("com.oplus.melody"),
        )
        val merged = MelodyAnchorReport.merge(
            first,
            installId = "abc",
            hostPackage = "com.oplus.melody",
            version = "17.6.3",
            processReport = process("com.oplus.melody:fg"),
        )

        assertEquals(2, merged.processes.size)
        assertEquals(4, merged.processes.sumOf { it.hits })
        assertEquals("17.6.3", merged.version)
    }

    @Test
    fun merge_differentInstall_startsFresh() {
        val first = MelodyAnchorReport.merge(
            null,
            installId = "old",
            hostPackage = "com.oplus.melody",
            version = "17.6.3",
            processReport = process("com.oplus.melody"),
        )
        val merged = MelodyAnchorReport.merge(
            first,
            installId = "new",
            hostPackage = "com.oplus.melody",
            version = "17.7.0",
            processReport = process("com.oplus.melody:fg"),
        )

        assertEquals("new", merged.installId)
        assertEquals(1, merged.processes.size)
        assertEquals("17.7.0", merged.version)
    }

    @Test
    fun processCodec_roundTripsWithoutHostEnvelope() {
        val encoded = MelodyAnchorBroadcast.encodeProcess(process("com.oplus.melody:fg"))
        val decoded = requireNotNull(MelodyAnchorBroadcast.decodeProcess(encoded))
        assertEquals("com.oplus.melody:fg", decoded.processName)
        assertEquals(3, decoded.total)
    }

    @Test
    fun trustedHosts_andRelocatableClassRule() {
        assertTrue(MelodyAnchorBroadcast.TRUSTED_HOSTS.contains("com.oplus.melody"))
        assertTrue(MelodyAnchorBroadcast.TRUSTED_HOSTS.contains("com.oplus.wirelesssettings"))
        assertFalse(MelodyAnchorBroadcast.TRUSTED_HOSTS.contains("com.evil.app"))
        // Real anchors, including the R8 short packages that the first cut wrongly rejected.
        assertTrue(MelodyAnchorBroadcast.isRelocatableHostClass("com.oplus.melody.model.repository.earphone.J"))
        assertTrue(MelodyAnchorBroadcast.isRelocatableHostClass("c7.b"))
        assertTrue(MelodyAnchorBroadcast.isRelocatableHostClass("d7.a"))
        assertTrue(MelodyAnchorBroadcast.isRelocatableHostClass("D0.d"))
        assertTrue(MelodyAnchorBroadcast.isRelocatableHostClass("A9.f"))
        assertTrue(MelodyAnchorBroadcast.isRelocatableHostClass("n7.e\$a"))
        assertTrue(MelodyAnchorBroadcast.isRelocatableHostClass("Ba.z"))
        assertTrue(MelodyAnchorBroadcast.isRelocatableHostClass("com.coui.appcompat.preference.COUIMenuPreference"))
        // Platform / stdlib / bundled libraries are never anchors.
        assertFalse(MelodyAnchorBroadcast.isRelocatableHostClass("androidx.preference.Preference"))
        assertFalse(MelodyAnchorBroadcast.isRelocatableHostClass("android.os.Handler"))
        assertFalse(MelodyAnchorBroadcast.isRelocatableHostClass("java.util.UUID"))
        assertFalse(MelodyAnchorBroadcast.isRelocatableHostClass("com.google.gson.Gson"))
    }
}

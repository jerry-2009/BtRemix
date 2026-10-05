package com.Fusion.Btremix.melody.bridge

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Fusion.Btremix.melody.api.MelodyAnchorBroadcast
import com.Fusion.Btremix.melody.api.MelodyAnchorEntry
import com.Fusion.Btremix.melody.api.MelodyAnchorLevel
import com.Fusion.Btremix.melody.api.MelodyAnchorPrefs
import com.Fusion.Btremix.melody.api.MelodyAnchorProcessReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M6: the anchor broadcast has to land in the module preference the injected host reads back, even with
 * BtRemix in the background. Delivered here with an explicit component broadcast so the assertion does
 * not depend on the real Melody package being installed.
 */
@RunWith(AndroidJUnit4::class)
class MelodyHostAnchorsReceiverTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearReport() {
        context.getSharedPreferences(MelodyAnchorPrefs.GROUP, Context.MODE_PRIVATE)
            .edit()
            .remove(MelodyAnchorPrefs.REPORT)
            .remove(MelodyAnchorPrefs.SEEN_INSTALL)
            .remove(MelodyAnchorPrefs.SEEN_VERSION)
            .remove(MelodyAnchorPrefs.NOTIFIED_VERSION)
            .remove(MelodyAnchorPrefs.REPORT_VERSION)
            .commit()
    }

    @Test
    fun broadcast_persistsAnchorReport() {
        val installId = "test-install-42"
        val processReport = MelodyAnchorProcessReport(
            processName = "com.oplus.melody",
            anchors = listOf(
                MelodyAnchorEntry("redirect.v0", "com.oplus.melody.model.repository.earphone.J", "v0", MelodyAnchorLevel.Baseline),
                MelodyAnchorEntry("panel.group_observer", null, null, MelodyAnchorLevel.Missing),
            ),
        )
        val intent = Intent(MelodyAnchorBroadcast.ACTION)
            .setClass(context, MelodyHostAnchorsReceiver::class.java)
            .putExtra(MelodyAnchorBroadcast.EXTRA_PROTOCOL, MelodyAnchorBroadcast.PROTOCOL)
            .putExtra(MelodyAnchorBroadcast.EXTRA_INSTALL_ID, installId)
            .putExtra(MelodyAnchorBroadcast.EXTRA_HOST_PACKAGE, "com.oplus.melody")
            .putExtra(MelodyAnchorBroadcast.EXTRA_PROCESS, "com.oplus.melody")
            .putExtra(MelodyAnchorBroadcast.EXTRA_ANCHORS, MelodyAnchorBroadcast.encodeProcess(processReport))

        context.sendBroadcast(intent)

        val deadline = System.currentTimeMillis() + 5_000
        var stored = MelodyAnchorStore.read(context)
        while (stored == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            stored = MelodyAnchorStore.read(context)
        }
        val report = requireNotNull(stored) { "receiver did not persist the report" }
        assertEquals(installId, report.installId)
        assertEquals("com.oplus.melody", report.hostPackage)
        assertEquals(2, report.processes.single().total)
        assertTrue(report.processes.single().misses.map { it.id }.contains("panel.group_observer"))
    }

    @Test
    fun wrongProtocol_isIgnored() {
        val intent = Intent(MelodyAnchorBroadcast.ACTION)
            .setClass(context, MelodyHostAnchorsReceiver::class.java)
            .putExtra(MelodyAnchorBroadcast.EXTRA_PROTOCOL, 99)
            .putExtra(MelodyAnchorBroadcast.EXTRA_INSTALL_ID, "nope")
            .putExtra(MelodyAnchorBroadcast.EXTRA_HOST_PACKAGE, "com.oplus.melody")
            .putExtra(MelodyAnchorBroadcast.EXTRA_PROCESS, "com.oplus.melody")
            .putExtra(MelodyAnchorBroadcast.EXTRA_ANCHORS, "{}")

        context.sendBroadcast(intent)
        Thread.sleep(300)

        assertEquals(null, MelodyAnchorStore.read(context))
    }
}

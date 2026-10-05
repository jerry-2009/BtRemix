package com.fusion.melodyLinkNeo.melody.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fusion.melodyLinkNeo.BtRemixApplication
import com.fusion.melodyLinkNeo.definition.json.DefinitionJsonCodec
import com.fusion.melodyLinkNeo.melody.api.MelodyBridgeResult
import com.fusion.melodyLinkNeo.melody.api.MelodyLifecycleWire
import com.fusion.melodyLinkNeo.melody.config.MelodyManagedDevice
import com.fusion.melodyLinkNeo.melody.projection.MelodyProjectionBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented contract for the bridge service (MELODY_BRIDGE_SPEC §13.2, milestone M2b).
 *
 * The JVM suite covers the pure decisions; this one proves the Android half - the service binds, hands
 * back a working [IMelodyBridge], and degrades instead of throwing when nothing is managed. Binding from
 * the instrumentation process means `Binder.getCallingUid()` equals BtRemix's own UID, which the call
 * policy treats as self (the remote `com.oplus.melody` acceptance is a manual on-device step).
 *
 * The unknown-MAC cases are also this milestone's graceful-degradation criterion: a panel that opens
 * before any headset is connected must get "disconnected, no state" rather than a failed transaction.
 */
@RunWith(AndroidJUnit4::class)
class MelodySessionServiceTest {

    private lateinit var context: Context
    private val connected = CountDownLatch(1)
    private var bridge: IMelodyBridge? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bridge = service?.let { IMelodyBridge.Stub.asInterface(it) }
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridge = null
        }
    }

    @Before
    fun bindService() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent().setComponent(ComponentName(context.packageName, MelodySessionService::class.java.name))
        assertTrue("bindService was refused", context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue("service did not connect in time", connected.await(5, TimeUnit.SECONDS))
    }

    @After
    fun unbindService() {
        runCatching { context.unbindService(connection) }
        // The projection test seeds a managed device; drop it so later cases see a clean registry.
        if (this::context.isInitialized) {
            runBlocking { (context.applicationContext as BtRemixApplication).melodySupport.clearManaged() }
        }
    }

    @Test
    fun binder_isReachableAndListsTheManagedSet() {
        val api = requireNotNull(bridge)
        val app = context.applicationContext as BtRemixApplication

        // No session is registered, so the list is exactly the paired-device managed set.
        assertEquals(app.melodySupport.managedMacs().sorted(), api.listManagedMacs().sorted())
    }

    @Test
    fun resolveProjection_returnsNullForUnmanagedMac() {
        assertNull(requireNotNull(bridge).resolveProjection(MAC))
    }

    @Test
    fun resolveProjection_returnsEnvelopeForManagedMac() {
        val app = context.applicationContext as BtRemixApplication
        val definition = DefinitionJsonCodec.decode(MANAGED_DEFINITION)
        runBlocking {
            app.melodySupport.seedManaged(
                listOf(
                    MelodyManagedDevice(
                        mac = MANAGED_MAC,
                        name = "WF-1000XM3",
                        definition = definition,
                        melody = requireNotNull(definition.melody),
                    ),
                ),
            )
        }

        val envelope = requireNotNull(requireNotNull(bridge).resolveProjection(MANAGED_MAC))

        assertTrue(
            "missing envelope version",
            envelope.contains("\"version\":${MelodyProjectionBuilder.ENVELOPE_VERSION}"),
        )
        assertTrue("missing mac", envelope.contains("\"mac\":\"$MANAGED_MAC\""))
        assertTrue("missing synthesised identity", envelope.contains("Sony WF-1000XM3"))
        assertTrue("managed MAC missing from listManagedMacs", requireNotNull(bridge).listManagedMacs().contains(MANAGED_MAC))
    }

    @Test
    fun unknownMac_returnsDisconnectedSnapshotInsteadOfFailing() {
        val snapshot = requireNotNull(requireNotNull(bridge).snapshot(MAC))

        assertEquals(MelodyLifecycleWire.DISCONNECTED, snapshot.lifecycle)
        assertTrue(snapshot.state.isEmpty())
    }

    @Test
    fun resolveSupport_reportsUnmanagedWithoutThrowing() {
        val info = requireNotNull(bridge).resolveSupport(MAC)

        assertNotNull(info)
        assertFalse(info!!.managed)
    }

    @Test
    fun executeWithoutASession_returnsSessionUnavailable() {
        val code = requireNotNull(bridge).execute(MAC, "anc.set", null)

        assertEquals(MelodyBridgeResult.ERROR_SESSION_UNAVAILABLE, code)
    }

    private companion object {
        const val MAC = "AA:BB:CC:DD:EE:00"
        const val MANAGED_MAC = "14:3F:A6:02:5F:B0"

        val MANAGED_DEFINITION = """
            {
              "manifest": {
                "id": "sony.wf1000xm3",
                "displayName": "Sony WF-1000XM3",
                "version": "1.0.0",
                "schemaVersion": 4,
                "matchers": [{ "type": "namePrefix", "value": "WF-1000XM3" }]
              },
              "melody": {
                "support": { "name": "Sony WF-1000XM3", "brand": "Sony", "productId": "0x0CE0" }
              }
            }
        """.trimIndent()
    }
}

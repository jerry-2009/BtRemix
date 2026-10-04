package com.Fusion.Btremix.melody.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Fusion.Btremix.melody.api.MelodyBridgeResult
import com.Fusion.Btremix.melody.api.MelodyLifecycleWire
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    }

    @Test
    fun binder_isReachableAndListsNothingWithoutSessions() {
        val api = requireNotNull(bridge)
        assertTrue(api.listManagedMacs().isEmpty())
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
    }
}

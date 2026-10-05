package com.fusion.melodyLinkNeo.melody.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fusion.melodyLinkNeo.BtRemixApplication
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.device.runtime.ActionResult
import com.fusion.melodyLinkNeo.device.runtime.DeviceAction
import com.fusion.melodyLinkNeo.device.runtime.DeviceActionHandler
import com.fusion.melodyLinkNeo.device.runtime.DeviceEvent
import com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState
import com.fusion.melodyLinkNeo.device.runtime.InMemoryStateStore
import com.fusion.melodyLinkNeo.device.runtime.StateStore
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import com.fusion.melodyLinkNeo.melody.api.MelodyLifecycleWire
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2b acceptance with a fake session instead of hardware (MELODY_BRIDGE_SPEC §12 M2b, §13.2).
 *
 * The milestone's criterion is "BtRemix and Melody show the same snapshot". Two halves are checkable here:
 *
 * - the payload half, asserted below: a session registered in the process-scoped registry comes back out of
 *   the *binder* with the same lifecycle and state keys, which exercises the AIDL parcelables and
 *   `MelodyBundleCodec` over a real transaction;
 * - the cross-process half, which needs the host process to be alive: while this test holds the fake
 *   session, `MelodySessionService` runs and the doorbell carries the binder into `com.oplus.melody`, whose
 *   injected client logs `melody.bridge.snapshot side=melody mac=... lifecycle=Ready keys=2`. Run
 *   `adb logcat -s BtRemixMelody` (or HANDOFF_MELODY_M2B.md §8.3) to see both sides of that line.
 *
 * The fake session keeps the test hardware-free on purpose (AGENTS.md: "use fake BLE backends so tests do
 * not require real devices"); the real headset path is the manual step in §8.3.
 */
@RunWith(AndroidJUnit4::class)
class MelodyBridgeSessionFlowTest {

    private var bridge: IMelodyBridge? = null
    private val connected = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bridge = service?.let { IMelodyBridge.Stub.asInterface(it) }
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridge = null
        }
    }

    @Test
    fun managedSession_isExposedThroughTheBridgeAndPushedToTheOtherProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as BtRemixApplication
        val session = FakeSession(MAC)

        // Registering into the shared registry is what makes the Compose page and the Melody bridge look at
        // one session; it also makes BtRemixApplication start MelodySessionService (doorbell transport).
        app.sessions.register(MAC, session)
        try {
            assertNotNull("bridge did not connect", bindService(context))
            val api = requireNotNull(bridge)

            assertTrue("managed MAC missing from listManagedMacs", api.listManagedMacs().contains(MAC))

            val snapshot = requireNotNull(api.snapshot(MAC))
            assertEquals(MelodyLifecycleWire.READY, snapshot.lifecycle)
            assertEquals(setOf(KEY_MODE, KEY_BATTERY), snapshot.stateKeys)

            // Hold the session long enough for the doorbell round trip and the listener push, so the device
            // log shows the host side of the same snapshot (see the class note). Kept short on purpose:
            // ColorOS can freeze a background instrumentation process, and the bridge work is done in the
            // first second after the doorbell.
            Thread.sleep(HOLD_MS)
        } finally {
            runCatching { context.unbindService(connection) }
            bridge = null
            app.sessions.remove(MAC)
        }
    }

    private fun bindService(context: Context): IMelodyBridge? {
        val intent = Intent().setComponent(
            ComponentName(context.packageName, MelodySessionService::class.java.name),
        )
        assertTrue("bindService was refused", context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue("service did not connect in time", connected.await(5, TimeUnit.SECONDS))
        return bridge
    }

    /** Minimal [com.fusion.melodyLinkNeo.device.runtime.ProtocolSession] with a Ready lifecycle and two states. */
    private class FakeSession(mac: String) : com.fusion.melodyLinkNeo.device.runtime.ProtocolSession {

        private val lifecycleImpl = MutableStateFlow<DeviceLifecycleState>(DeviceLifecycleState.Ready)
        private val eventsImpl = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 4)
        private val store = InMemoryStateStore()

        override val device = BleDevice(id = mac, name = "Fake headset", address = mac)
        override val lifecycle: StateFlow<DeviceLifecycleState> = lifecycleImpl.asStateFlow()
        override val state: StateStore = store
        override val stateStore: StateStore = store
        override val events: SharedFlow<DeviceEvent> = eventsImpl.asSharedFlow()

        init {
            runBlocking {
                store.set(KEY_MODE, StateValue.IntValue(1))
                store.set(KEY_BATTERY, StateValue.IntValue(88))
            }
        }

        override suspend fun execute(action: DeviceAction): ActionResult = ActionResult.Success()

        override fun registerAction(id: String, handler: DeviceActionHandler) = Unit

        override suspend fun close() {
            lifecycleImpl.value = DeviceLifecycleState.Disconnected
        }
    }

    private companion object {
        const val MAC = "AA:BB:CC:DD:EE:FF"
        const val KEY_MODE = "anc.mode"
        const val KEY_BATTERY = "battery.level"
        const val HOLD_MS = 4_000L
    }
}

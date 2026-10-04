package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.device.runtime.RuntimeError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle flattening for the snapshot payload (MELODY_BRIDGE_SPEC §8).
 *
 * `DeviceLifecycleState.Error` carries a `RuntimeError` instance, which cannot cross AIDL; losing it
 * silently would make the panel show a bare "Error". These cases pin the name/message split and the
 * best-effort reconstruction used when a late-joining panel rebuilds the state.
 */
class MelodyLifecycleWireTest {

    @Test
    fun everyStateHasAStableName() {
        assertEquals("Ready", MelodyLifecycleWire.nameOf(DeviceLifecycleState.Ready))
        assertEquals("Disconnected", MelodyLifecycleWire.nameOf(DeviceLifecycleState.Disconnected))
        assertEquals("RefreshingState", MelodyLifecycleWire.nameOf(DeviceLifecycleState.RefreshingState))
        assertEquals("Error", MelodyLifecycleWire.nameOf(DeviceLifecycleState.Error(RuntimeError.InitializationFailed("boom"))))
    }

    @Test
    fun errorMessageIsCarriedAlongsideTheName() {
        val state = DeviceLifecycleState.Error(RuntimeError.ConnectionFailed("link lost"))

        assertEquals("Error", MelodyLifecycleWire.nameOf(state))
        assertEquals("link lost", MelodyLifecycleWire.messageOf(state))
    }

    @Test
    fun nonErrorStatesHaveNoMessage() {
        assertNull(MelodyLifecycleWire.messageOf(DeviceLifecycleState.Ready))
    }

    @Test
    fun stateOf_rebuildsBestEffortErrorAndUnknownFallsBackToDisconnected() {
        val rebuilt = MelodyLifecycleWire.stateOf("Error", "link lost")
        assertTrue(rebuilt is DeviceLifecycleState.Error)
        assertEquals("link lost", (rebuilt as DeviceLifecycleState.Error).error.message)

        assertEquals(DeviceLifecycleState.Ready, MelodyLifecycleWire.stateOf("Ready", null))
        assertEquals(DeviceLifecycleState.Disconnected, MelodyLifecycleWire.stateOf("something-new", null))
        assertEquals(DeviceLifecycleState.Disconnected, MelodyLifecycleWire.stateOf(null, null))
    }
}

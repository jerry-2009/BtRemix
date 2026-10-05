package com.fusion.melodyLinkNeo.melody.hook.injection

import com.fusion.melodyLinkNeo.melody.api.MelodyDeviceInfoProjection
import com.fusion.melodyLinkNeo.melody.api.MelodyEarphoneBattery
import com.fusion.melodyLinkNeo.melody.api.MelodyEarphoneProjection
import com.fusion.melodyLinkNeo.melody.api.MelodyLifecycleWire
import com.fusion.melodyLinkNeo.definition.api.MelodyAncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.3a wiring check for the getter adapter (`HANDOFF_MELODY_M4_PLAN.md` §3 M4.3a).
 *
 * The host's `EarphoneDTO` is R8-minified but its getter names are the public Kotlin API the header
 * itself calls. The stand-in below exposes exactly those names, so a rename of a getter the adapter
 * projects shows up here instead of only on a phone. `null` is asserted explicitly: it is the adapter's
 * "leave the official value alone" contract for every field we cannot fill.
 */
class MelodyEarphoneAdapterTest {

    private val ready = MelodyEarphoneProjection.from(
        MelodyLifecycleWire.READY,
        MelodyEarphoneBattery(left = 80, right = 90, box = 55),
    )

    @Test
    fun everyProjectedGetter_resolvesOnAHostShapedDto() {
        for (getter in MelodyEarphoneAdapter.GETTERS) {
            val method = runCatching { FakeEarphoneDto::class.java.getDeclaredMethod(getter) }.getOrNull()
            assertTrue("missing host getter $getter", method != null)
        }
    }

    @Test
    fun readyProjection_overridesConnectionStates() {
        for (getter in listOf(
            "getConnectionState",
            "getHeadsetConnectionState",
            "getAclConnectionState",
            "getA2dpConnectionState",
        )) {
            assertEquals(
                "$getter",
                MelodyDeviceInfoProjection.STATE_CONNECTED,
                MelodyEarphoneAdapter.overrideFor(getter, ready),
            )
        }
    }

    @Test
    fun readyProjection_overridesBatteryOnBothSpellings() {
        assertEquals(80, MelodyEarphoneAdapter.overrideFor("getLeftBattery", ready))
        assertEquals(80, MelodyEarphoneAdapter.overrideFor("getHeadsetLeftBattery", ready))
        assertEquals(90, MelodyEarphoneAdapter.overrideFor("getRightBattery", ready))
        assertEquals(90, MelodyEarphoneAdapter.overrideFor("getHeadsetRightBattery", ready))
        assertEquals(55, MelodyEarphoneAdapter.overrideFor("getBoxBattery", ready))
        assertEquals(55, MelodyEarphoneAdapter.overrideFor("getHeadsetBoxBattery", ready))
        assertEquals(true, MelodyEarphoneAdapter.overrideFor("isBatteryInfoReceived", ready))
        assertEquals(true, MelodyEarphoneAdapter.overrideFor("isCapabilityReady", ready))
    }

    @Test
    fun droppedSession_reportsDisconnectedSoTheNativeHeaderReturns() {
        val dropped = MelodyEarphoneProjection.from(MelodyLifecycleWire.DISCONNECTED, null)

        assertEquals(
            MelodyDeviceInfoProjection.STATE_DISCONNECTED,
            MelodyEarphoneAdapter.overrideFor("getConnectionState", dropped),
        )
    }

    @Test
    fun batteryAbsent_leavesEveryBatteryGetterToTheHost() {
        val projection = MelodyEarphoneProjection.from(MelodyLifecycleWire.READY, null)

        for (getter in listOf(
            "getLeftBattery",
            "getRightBattery",
            "getBoxBattery",
            "getHeadsetLeftBattery",
            "getHeadsetRightBattery",
            "getHeadsetBoxBattery",
            "isBatteryInfoReceived",
        )) {
            assertNull("$getter", MelodyEarphoneAdapter.overrideFor(getter, projection))
        }
    }

    @Test
    fun halfOpenSession_leavesCapabilityReadinessToTheHost() {
        val projection = MelodyEarphoneProjection.from(MelodyLifecycleWire.CONNECTING, null)

        assertNull(MelodyEarphoneAdapter.overrideFor("isCapabilityReady", projection))
    }

    @Test
    fun anUnknownGetter_isNeverOverridden() {
        assertNull(MelodyEarphoneAdapter.overrideFor("getSomethingElse", ready))
    }

    @Test
    fun ancMode_projectsTheMatchingProtocolIndexAndLeavesItAbsentOtherwise() {
        val modes = listOf(
            MelodyAncMode(modeType = 5, protocolIndex = 0, state = "anc"),
            MelodyAncMode(modeType = 1, protocolIndex = 1, state = "off"),
        )

        assertEquals(
            1,
            MelodyEarphoneAdapter.overrideFor(
                "getNoiseReductionModeIndex",
                MelodyEarphoneProjection.from(MelodyLifecycleWire.READY, null, "off", modes),
            ),
        )
        assertNull(
            MelodyEarphoneAdapter.overrideFor(
                "getNoiseReductionModeIndex",
                MelodyEarphoneProjection.from(MelodyLifecycleWire.READY, null),
            ),
        )
    }

    /**
     * Host shape: the real `EarphoneDTO` getter names, each answering its native (disconnected) value.
     * Nothing here is called - the test only proves the names the adapter resolves are the names the host
     * exposes.
     */
    @Suppress("unused")
    private class FakeEarphoneDto {
        fun getMacAddress(): String = "14:3F:A6:02:5F:B0"
        fun getConnectionState(): Int = 0
        fun getHeadsetConnectionState(): Int = 0
        fun getAclConnectionState(): Int = 0
        fun getA2dpConnectionState(): Int = 0
        fun getNoiseReductionModeIndex(): Int = 0
        fun getLeftBattery(): Int = 0
        fun getRightBattery(): Int = 0
        fun getBoxBattery(): Int = 0
        fun getHeadsetLeftBattery(): Int = 0
        fun getHeadsetRightBattery(): Int = 0
        fun getHeadsetBoxBattery(): Int = 0
        fun isCapabilityReady(): Boolean = false
        fun isBatteryInfoReceived(): Boolean = false
    }
}

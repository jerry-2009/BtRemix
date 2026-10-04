package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.3a acceptance for the pure header projection (`HANDOFF_MELODY_M4_PLAN.md` §3 M4.3a, D-6).
 *
 * The connection half has to mirror M3.4's `profileStateOf` exactly, and the battery half has to be
 * *additive*: a level the Definition does not publish stays absent instead of becoming `0`, because the
 * injection treats `null` as "return the host's own value".
 */
class MelodyEarphoneProjectionTest {

    @Test
    fun readySession_projectsConnectedProfilesAndClaimsCapabilityReadiness() {
        val projection = MelodyEarphoneProjection.from(MelodyLifecycleWire.READY, null)

        assertEquals(MelodyDeviceInfoProjection.STATE_CONNECTED, projection.connectionState)
        assertEquals(MelodyDeviceInfoProjection.STATE_CONNECTED, projection.headsetState)
        assertEquals(MelodyDeviceInfoProjection.STATE_CONNECTED, projection.aclState)
        assertEquals(MelodyDeviceInfoProjection.STATE_CONNECTED, projection.a2dpState)
        assertEquals(true, projection.capabilityReady)
        assertTrue(projection.connected)
    }

    @Test
    fun refreshingState_countsAsReady() {
        val projection = MelodyEarphoneProjection.from(MelodyLifecycleWire.REFRESHING_STATE, null)

        assertTrue(projection.connected)
        assertEquals(true, projection.capabilityReady)
    }

    @Test
    fun halfOpenSession_reportsConnectingWithoutClaimingReadiness() {
        val projection = MelodyEarphoneProjection.from(MelodyLifecycleWire.CONNECTING, null)

        assertEquals(MelodyDeviceInfoProjection.STATE_CONNECTING, projection.connectionState)
        assertFalse(projection.connected)
        assertNull(projection.capabilityReady)
    }

    @Test
    fun droppedSession_fallsBackToDisconnectedSoTheNativeHeaderReturns() {
        val projection = MelodyEarphoneProjection.from(MelodyLifecycleWire.DISCONNECTED, null)

        assertEquals(MelodyDeviceInfoProjection.STATE_DISCONNECTED, projection.connectionState)
        assertFalse(projection.connected)
        assertNull(projection.capabilityReady)
    }

    @Test
    fun unknownOrMissingLifecycle_isDisconnected() {
        assertEquals(
            MelodyDeviceInfoProjection.STATE_DISCONNECTED,
            MelodyEarphoneProjection.from(null, null).connectionState,
        )
        assertEquals(
            MelodyDeviceInfoProjection.STATE_DISCONNECTED,
            MelodyEarphoneProjection.from("SomethingElse", null).connectionState,
        )
    }

    @Test
    fun batteryStates_mapLeftRightAndCase() {
        val battery = MelodyEarphoneBattery.ofStates(
            mapOf(
                "battery.left" to WireValue.Int32(80),
                "battery.right" to WireValue.Int32(90),
                "battery.case" to WireValue.Int32(55),
            ),
        )

        assertEquals(80, battery?.left)
        assertEquals(90, battery?.right)
        assertEquals(55, battery?.box)
    }

    @Test
    fun boxAlias_isAccepted() {
        val battery = MelodyEarphoneBattery.ofStates(mapOf("battery.box" to WireValue.Int32(42)))

        assertEquals(42, battery?.box)
    }

    @Test
    fun textAndLongEncodedLevels_areParsed() {
        val battery = MelodyEarphoneBattery.ofStates(
            mapOf(
                "battery.left" to WireValue.Text(" 66 "),
                "battery.right" to WireValue.Int64(77),
            ),
        )

        assertEquals(66, battery?.left)
        assertEquals(77, battery?.right)
        assertNull(battery?.box)
    }

    @Test
    fun partOfTheLevels_isStillEnough_toShowTheBattery() {
        val projection = MelodyEarphoneProjection.from(
            MelodyLifecycleWire.READY,
            MelodyEarphoneBattery(left = 12),
        )

        assertEquals(12, projection.battery?.left)
        assertNull(projection.battery?.right)
        assertEquals(true, projection.batteryInfoReceived)
    }

    @Test
    fun noLevels_leavesTheBatteryUntouched() {
        val projection = MelodyEarphoneProjection.from(MelodyLifecycleWire.READY, null)

        assertNull(projection.battery)
        assertNull(projection.batteryInfoReceived)
    }

    @Test
    fun anEmptyBattery_isTreatedAsAbsentInsteadOfAllZeroes() {
        val projection = MelodyEarphoneProjection.from(
            MelodyLifecycleWire.READY,
            MelodyEarphoneBattery(left = null, right = null, box = null),
        )

        assertNull(projection.battery)
        assertNull(projection.batteryInfoReceived)
        assertNull(MelodyEarphoneBattery.ofStates(emptyMap()))
        assertNull(MelodyEarphoneBattery.ofStates(mapOf("unrelated" to WireValue.Int32(1))))
    }
}

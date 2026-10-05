package com.fusion.melodyLinkNeo.melody.api

import com.fusion.melodyLinkNeo.definition.api.MelodyAncMode
import com.fusion.melodyLinkNeo.definition.api.MelodyAncStrengthDefinition
import com.fusion.melodyLinkNeo.definition.api.MelodyAncStrengthLevel
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

    // --- M4.3b Step 1: ANC mode -> host protocol index -----------------------------------------

    @Test
    fun ancMode_mapsToTheMatchingProtocolIndex() {
        val projection = MelodyEarphoneProjection.from(
            MelodyLifecycleWire.READY,
            null,
            ancMode = "ambient",
            ancModes = ANC_MODES,
        )

        assertEquals(2, projection.noiseModeIndex)
        assertTrue(projection.ancModeMatched)
    }

    @Test
    fun ancModeOutsideTheTable_fallsBackToTheOffSlot() {
        val projection = MelodyEarphoneProjection.from(
            MelodyLifecycleWire.READY,
            null,
            ancMode = "something-else",
            ancModes = ANC_MODES,
        )

        // Off is modeType 1 with protocolIndex 1 in this table; `matched=false` says the live value
        // was not in the table (the panel shows Off rather than keeping a stale highlight).
        assertEquals(1, projection.noiseModeIndex)
        assertFalse(projection.ancModeMatched)
    }

    @Test
    fun noAncModeOrNoTable_projectsNothing() {
        assertNull(MelodyEarphoneProjection.from(MelodyLifecycleWire.READY, null).noiseModeIndex)
        assertNull(
            MelodyEarphoneProjection.from(MelodyLifecycleWire.READY, null, "anc", emptyList()).noiseModeIndex,
        )
    }

    // --- M4.3b D-15: ancLevel -> native「降噪效果」child index ------------------------------------

    @Test
    fun ancLevel_mapsToTheNearestStrengthChild() {
        assertEquals(10, projectionFor(level = 1).noiseModeIndex)
        assertEquals(10, projectionFor(level = 5).noiseModeIndex) // |5-1|=4 beats |5-10|=5
        assertEquals(11, projectionFor(level = 10).noiseModeIndex)
        assertEquals(11, projectionFor(level = 15).noiseModeIndex) // tie keeps the lower position
        assertEquals(12, projectionFor(level = 20).noiseModeIndex)
    }

    @Test
    fun ancLevel_isClampedToTheStrengthEnds() {
        assertEquals(10, projectionFor(level = -5).noiseModeIndex)
        assertEquals(12, projectionFor(level = 99).noiseModeIndex)
    }

    @Test
    fun ancWithoutALiveLevel_fallsBackToTheParentSlot() {
        // The parent index keeps the mode cell highlighted; the host's own summary then has no value.
        assertEquals(0, projectionFor(level = null).noiseModeIndex)
        assertEquals(
            0,
            MelodyEarphoneProjection.from(MelodyLifecycleWire.READY, null, "anc", ANC_MODES, 10)
                .noiseModeIndex,
        )
    }

    @Test
    fun nonAncMode_neverProjectsAStrengthChild() {
        assertEquals(
            2,
            MelodyEarphoneProjection.from(MelodyLifecycleWire.READY, null, "ambient", ANC_MODES, 1, STRENGTH)
                .noiseModeIndex,
        )
    }

    private fun projectionFor(level: Int?): MelodyEarphoneProjection =
        MelodyEarphoneProjection.from(
            MelodyLifecycleWire.READY,
            null,
            ancMode = "anc",
            ancModes = ANC_MODES,
            ancLevel = level,
            ancStrength = STRENGTH,
        )

    private companion object {
        val STRENGTH = MelodyAncStrengthDefinition(
            state = "ancLevel",
            action = "anc.setLevel",
            levels = listOf(
                MelodyAncStrengthLevel(modeType = 3, protocolIndex = 10, level = 1),
                MelodyAncStrengthLevel(modeType = 8, protocolIndex = 11, level = 10),
                MelodyAncStrengthLevel(modeType = 4, protocolIndex = 12, level = 20),
            ),
        )

        val ANC_MODES = listOf(
            MelodyAncMode(modeType = 5, protocolIndex = 0, state = "anc", label = "Noise canceling"),
            MelodyAncMode(modeType = 1, protocolIndex = 1, state = "off", label = "Off"),
            MelodyAncMode(modeType = 2, protocolIndex = 2, state = "ambient", label = "Ambient sound"),
            MelodyAncMode(modeType = 10, protocolIndex = 3, state = "wind", label = "Wind noise reduction"),
        )
    }
}

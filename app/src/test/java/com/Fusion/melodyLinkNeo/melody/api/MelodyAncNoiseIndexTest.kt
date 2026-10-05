package com.fusion.melodyLinkNeo.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M5.1 follow-up acceptance for the no-MAC btsdk noise value (`HANDOFF_MELODY_M5_PLAN.md` §4 M5.1 ⑨):
 * the device-centre card's `CurrentNoiseModeInfo.getCurrentNoiseReductionModeIndex()` may only be
 * answered from our projection when exactly one device is managed; anything else stays fail-open.
 */
class MelodyAncNoiseIndexTest {

    @Test
    fun singleManagedMac_resolvesToItsProjectedIndex() {
        val target = MelodyAncNoiseIndex.resolve(listOf("14:3F:A6:02:5F:B0")) { 11 }

        assertEquals(MelodyAncNoiseIndex.Target("14:3F:A6:02:5F:B0", 11), target)
    }

    @Test
    fun macCaseAndWhitespace_areNormalizedBeforeResolving() {
        val target = MelodyAncNoiseIndex.resolve(listOf(" 14:3f:a6:02:5f:b0 ", "14:3F:A6:02:5F:B0")) { 3 }

        assertEquals(MelodyAncNoiseIndex.Target("14:3F:A6:02:5F:B0", 3), target)
    }

    @Test
    fun twoManagedMacs_areAmbiguousAndStayFailOpen() {
        val target = MelodyAncNoiseIndex.resolve(
            listOf("14:3F:A6:02:5F:B0", "AA:BB:CC:DD:EE:FF"),
        ) { 11 }

        assertNull(target)
    }

    @Test
    fun noManagedMac_staysFailOpen() {
        assertNull(MelodyAncNoiseIndex.resolve(emptyList()) { 11 })
    }

    @Test
    fun unknownProjection_staysFailOpen() {
        assertNull(MelodyAncNoiseIndex.resolve(listOf("14:3F:A6:02:5F:B0")) { null })
    }
}

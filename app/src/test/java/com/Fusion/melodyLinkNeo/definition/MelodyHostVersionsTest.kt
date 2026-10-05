package com.fusion.melodyLinkNeo.definition

import com.fusion.melodyLinkNeo.definition.api.MelodyHostVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5.4 D-21/D-26: the `melody.support.hostVersions` range expression. */
class MelodyHostVersionsTest {

    @Test
    fun blankOrMissingSpec_meansNoRestriction() {
        assertNull(MelodyHostVersions.parse(null))
        assertNull(MelodyHostVersions.parse(""))
        assertNull(MelodyHostVersions.parse("   "))
    }

    @Test
    fun bareVersion_meansAtLeastThatVersion() {
        val range = requireNotNull(MelodyHostVersions.parse("17.6.3"))

        assertTrue(range.matches("17.6.3"))
        assertTrue(range.matches("17.6.4"))
        assertTrue(range.matches("18.0.0"))
        assertFalse(range.matches("17.6.2"))
        assertEquals("17.6.3", range.spec)
    }

    @Test
    fun interval_matchesEveryPredicate() {
        val range = requireNotNull(MelodyHostVersions.parse(">=17.6.3 <18"))

        assertTrue(range.matches("17.6.3"))
        assertTrue(range.matches("17.9.9"))
        assertFalse(range.matches("17.6.2"))
        assertFalse(range.matches("18.0.0"))
        // The upper bound is exclusive, the lower inclusive.
        assertTrue(requireNotNull(MelodyHostVersions.parse(">=17.6.3")).matches("17.6.3"))
        assertFalse(requireNotNull(MelodyHostVersions.parse("<=17.6.3")).matches("17.6.4"))
    }

    @Test
    fun comparison_isNumericPerSegment_notLexical() {
        assertTrue(requireNotNull(MelodyHostVersions.parse(">17.6.9")).matches("17.6.10"))
        assertTrue(requireNotNull(MelodyHostVersions.parse(">=17.6")).matches("17.6.0"))
        assertTrue(requireNotNull(MelodyHostVersions.parse("<17.10")).matches("17.9.9"))
    }

    @Test
    fun suffixesAreTolerated() {
        val range = requireNotNull(MelodyHostVersions.parse(">=17.6.3"))

        assertTrue(range.matches("17.6.3_beta"))
        assertTrue(range.matches(" 17.6.3 "))
    }

    @Test
    fun unreadableVersions_neverMatch() {
        val range = requireNotNull(MelodyHostVersions.parse(">=17.6.3"))

        assertFalse(range.matches(null))
        assertFalse(range.matches(""))
        assertFalse(range.matches("unknown"))
        assertFalse(MelodyHostVersions.isReadable(null))
        assertFalse(MelodyHostVersions.isReadable("unknown"))
        assertTrue(MelodyHostVersions.isReadable("17.6.3"))
    }

    @Test
    fun malformedSpec_isRejectedInsteadOfSilentlyMatching() {
        assertNull(MelodyHostVersions.parse(">="))
        assertNull(MelodyHostVersions.parse(">"))
        assertNull(MelodyHostVersions.parse("abc"))
        assertNull(MelodyHostVersions.parse(">=17.6.3 =="))
        assertNull(MelodyHostVersions.parse(">=17.6.3 <"))
    }
}

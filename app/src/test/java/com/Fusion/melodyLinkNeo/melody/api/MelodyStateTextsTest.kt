package com.fusion.melodyLinkNeo.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M4.3c acceptance for the row backfill formatting: the「高级功能」rows show the same text the BtRemix
 * Compose page would, and values that are not scalars are skipped rather than rendered as garbage.
 */
class MelodyStateTextsTest {

    @Test
    fun scalarsAreRenderedAsDisplayText() {
        assertEquals("true", MelodyStateTexts.textOf(WireValue.Bool(true)))
        assertEquals("false", MelodyStateTexts.textOf(WireValue.Bool(false)))
        assertEquals("17", MelodyStateTexts.textOf(WireValue.Int32(17)))
        assertEquals("42", MelodyStateTexts.textOf(WireValue.Int64(42)))
        assertEquals("Bright", MelodyStateTexts.textOf(WireValue.Text("Bright")))
    }

    @Test
    fun wholeFloatsLoseTheirDecimalPointAndFractionsKeepIt() {
        assertEquals("20", MelodyStateTexts.textOf(WireValue.Float32(20f)))
        assertEquals("1.5", MelodyStateTexts.textOf(WireValue.Float64(1.5)))
    }

    @Test
    fun structuredValuesAreSkipped() {
        assertNull(MelodyStateTexts.textOf(WireValue.Bytes(byteArrayOf(1, 2))))
        assertNull(MelodyStateTexts.textOf(WireValue.Items(listOf(WireValue.Int32(1)))))
        assertNull(MelodyStateTexts.textOf(WireValue.Fields(mapOf("a" to WireValue.Int32(1)))))
    }
}

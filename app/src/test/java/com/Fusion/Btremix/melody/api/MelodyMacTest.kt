package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MAC helpers shared by the M3.4 suppression layers: a host object's `String` fields are scanned
 * for something that *looks* like an address, so the pattern must accept exactly the shapes Android
 * hands out and reject the device names that sit next to them.
 */
class MelodyMacTest {

    @Test
    fun normalize_trimsAndUppercases() {
        assertEquals("14:3F:A6:02:5F:B0", MelodyMac.normalize("  14:3f:a6:02:5f:b0 "))
    }

    @Test
    fun isMacAddress_acceptsTheAndroidShapeOnly() {
        assertTrue(MelodyMac.isMacAddress("14:3F:A6:02:5F:B0"))
        assertTrue(MelodyMac.isMacAddress("aa:bb:cc:dd:ee:ff"))
        assertTrue(MelodyMac.isMacAddress(" 14:3F:A6:02:5F:B0 "))
        assertFalse(MelodyMac.isMacAddress("Sony WF-1000XM3"))
        assertFalse(MelodyMac.isMacAddress("14:3F:A6:02:5F"))
        assertFalse(MelodyMac.isMacAddress("14-3F-A6-02-5F-B0"))
        assertFalse(MelodyMac.isMacAddress(""))
        assertFalse(MelodyMac.isMacAddress("WH-1000XM3"))
    }
}

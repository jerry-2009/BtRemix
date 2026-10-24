package com.Fusion.Btremix.melody.hook.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MelodyWirelessSettingsInjectionTest {

    @Test
    fun whitelistNameMatchesDefinitionAndShortBluetoothName() {
        assertTrue(
            wirelessSettingsNameMatches(
                query = "WF-1000XM3",
                name = "Sony WF-1000XM3",
            ),
        )
    }

    @Test
    fun whitelistNameRejectsShortOrUnrelatedNames() {
        assertFalse(wirelessSettingsNameMatches("Buds", "Wireless Buds"))
        assertFalse(wirelessSettingsNameMatches("WH-1000XM5", "Sony WF-1000XM3"))
    }

    @Test
    fun melodyDeviceIdUsesOfficialAddressDigest() {
        assertEquals(
            "d41d8cd98f00b204e9800998ecf8427e",
            melodyDeviceId(""),
        )
        assertEquals(
            "96ceb3da36c70fa6623ddfb4486ef9e9",
            melodyDeviceId("14:3F:A6:02:5F:B0"),
        )
    }
}

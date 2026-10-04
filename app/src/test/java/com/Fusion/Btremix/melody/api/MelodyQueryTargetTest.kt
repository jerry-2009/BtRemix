package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.3 acceptance: the provider injection must recognise the same device the host is asking about,
 * whether the caller wrote the MAC into the URI query string (SystemUI) or into `selection` (a
 * `content query` shell), and whether the host asked by MAC or by `productId` + `deviceName`
 * (`COLOROS_MELODY_ANALYSIS.md` §B/§K2).
 */
class MelodyQueryTargetTest {

    private val mac = "14:3F:A6:02:5F:B0"

    @Test
    fun macFromUriQueryParameter() {
        val target = MelodyQueryTarget.parse(
            path = "/find_whitelist",
            queryParameters = mapOf("macAddress" to mac),
        )

        assertEquals(MelodyQueryPath.FIND_WHITELIST, target?.path)
        assertEquals(mac, target?.mac)
    }

    @Test
    fun macFromSelectionIsNormalised() {
        val target = MelodyQueryTarget.parse(
            path = "find_whitelist",
            selection = "macAddress=14:3f:a6:02:5f:b0",
        )

        assertEquals(mac, target?.mac)
    }

    @Test
    fun macFromQuotedSelection() {
        val target = MelodyQueryTarget.parse(
            path = "/find_whitelist",
            selection = "macAddress='$mac'",
        )

        assertEquals(mac, target?.mac)
    }

    @Test
    fun macFromSelectionArgsSubstitution() {
        val target = MelodyQueryTarget.parse(
            path = "/find_whitelist",
            selection = "macAddress=?",
            selectionArgs = arrayOf(mac),
        )

        assertEquals(mac, target?.mac)
    }

    @Test
    fun productIdAndDeviceNameFromUriQuery() {
        val target = MelodyQueryTarget.parse(
            path = "/find_whitelist",
            queryParameters = mapOf("productId" to "3296", "deviceName" to "Sony WF-1000XM3"),
        )

        assertEquals("3296", target?.productId)
        assertEquals("Sony WF-1000XM3", target?.deviceName)
        assertNull(target?.mac)
    }

    @Test
    fun productIdHexFormIsNormalisedToDecimal() {
        val target = MelodyQueryTarget.parse(
            path = "/find_whitelist",
            queryParameters = mapOf("productId" to "0CE0"),
        )

        assertEquals("3296", target?.productId)
    }

    @Test
    fun deviceNameWithSpacesFromSelection() {
        val target = MelodyQueryTarget.parse(
            path = "/find_whitelist",
            selection = "productId=3296 AND deviceName='Sony WF-1000XM3'",
        )

        assertEquals("3296", target?.productId)
        assertEquals("Sony WF-1000XM3", target?.deviceName)
    }

    @Test
    fun wearRowCarriesAddress() {
        val target = MelodyQueryTarget.parse(
            path = "/earphone_both_in_ear",
            selection = "address=$mac",
        )

        assertEquals(MelodyQueryPath.EARPHONE_BOTH_IN_EAR, target?.path)
        assertTrue(target!!.path.isWear)
        assertEquals(mac, target.mac)
    }

    @Test
    fun uriQueryWinsOverSelection() {
        val target = MelodyQueryTarget.parse(
            path = "/find_whitelist",
            queryParameters = mapOf("macAddress" to mac),
            selection = "macAddress=AA:BB:CC:DD:EE:FF",
        )

        assertEquals(mac, target?.mac)
    }

    @Test
    fun unknownOrMissingPathIsIgnored() {
        assertNull(MelodyQueryTarget.parse("/diagnosis_list", selection = "macAddress=$mac"))
        assertNull(MelodyQueryTarget.parse(null, selection = "macAddress=$mac"))
        assertNull(MelodyQueryTarget.parse("  ", selection = "macAddress=$mac"))
    }

    @Test
    fun matchesByMacIgnoresCaseAndShape() {
        val target = MelodyQueryTarget.parse("/find_whitelist", queryParameters = mapOf("macAddress" to mac))!!

        assertTrue(target.matches("14:3f:a6:02:5f:b0", "anything", null, null))
        assertFalse(target.matches("AA:BB:CC:DD:EE:FF", null, null, null))
    }

    @Test
    fun matchesByProductIdAcceptsEitherEncoding() {
        val target = MelodyQueryTarget.parse("/find_whitelist", queryParameters = mapOf("productId" to "3296"))!!

        // The managed identity carries both forms; the query only carries the decimal one.
        assertTrue(target.matches(null, "Sony WF-1000XM3", "3296", "000CE0"))
        assertFalse(target.matches(null, "Sony WF-1000XM3", "454672", "06F010"))
    }

    @Test
    fun matchesByProductIdAndDeviceNameNarrowsTheMatch() {
        val target = MelodyQueryTarget.parse(
            "/find_whitelist",
            queryParameters = mapOf("productId" to "3296", "deviceName" to "WF-1000XM3"),
        )!!

        assertTrue(target.matches(null, "Sony WF-1000XM3", "3296", "000CE0"))
        assertFalse(target.matches(null, "Some Other Headset", "3296", "000CE0"))
    }

    @Test
    fun matchesWithoutAnyIdentityIsFalse() {
        val target = MelodyQueryTarget.parse("/find_whitelist", queryParameters = emptyMap())!!

        assertFalse(target.matches(mac, "Sony WF-1000XM3", "3296", "000CE0"))
    }
}

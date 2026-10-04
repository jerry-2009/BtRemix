package com.Fusion.Btremix.definition.matcher

import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.core.classic.api.ClassicDevice
import com.Fusion.Btremix.definition.api.DeviceMatchInput
import com.Fusion.Btremix.definition.api.DeviceMatchRule
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.1 / MELODY_BRIDGE_SPEC §11.3: one set of Definition matchers has to serve BLE advertisements,
 * bonded classic devices and Melody provider queries. Name/address rules are neutral; advertised
 * service/manufacturer data cannot be invented for a classic or Melody input.
 */
class DeviceMatchInputTest {

    private val advertisement = DeviceMatchInput.Advertisement(
        BleScanResult(
            BleDevice("14:3F:A6:02:5F:B0", "WF-1000XM3"),
            rssi = -40,
            serviceUuids = listOf(SERVICE_UUID),
            manufacturerData = mapOf(COMPANY_ID to byteArrayOf(0x01, 0x02)),
        ),
    )
    private val classic = DeviceMatchInput.Classic(ClassicDevice("14:3F:A6:02:5F:B0", "WF-1000XM3"))
    private val melody = DeviceMatchInput.Melody(
        mac = "14:3F:A6:02:5F:B0",
        name = "WF-1000XM3",
        productId = "3296",
    )

    @Test
    fun nameExact_matchesEveryInputKind() {
        val rule = DeviceMatchRule.NameExact("WF-1000XM3")

        assertTrue(rule.matches(advertisement))
        assertTrue(rule.matches(classic))
        assertTrue(rule.matches(melody))
        assertFalse(rule.matches(DeviceMatchInput.Classic(ClassicDevice("AA:BB", "Something else"))))
    }

    @Test
    fun namePrefix_and_nameRegex_areNeutral() {
        assertTrue(DeviceMatchRule.NamePrefix("WF-").matches(classic))
        assertTrue(DeviceMatchRule.NameRegex(".*XM3").matches(melody))
        assertFalse(DeviceMatchRule.NamePrefix("WF-").matches(DeviceMatchInput.Classic(ClassicDevice("AA:BB", null))))
    }

    @Test
    fun addressRegex_matchesEveryInputKind() {
        val rule = DeviceMatchRule.AddressRegex("14:3F:.*")

        assertTrue(rule.matches(advertisement))
        assertTrue(rule.matches(classic))
        assertTrue(rule.matches(melody))
    }

    @Test
    fun serviceUuid_onlyMatchesAdvertisements() {
        val rule = DeviceMatchRule.ServiceUuid(SERVICE_UUID)

        assertTrue(rule.matches(advertisement))
        assertFalse("a classic device carries no advertised service UUID", rule.matches(classic))
        assertFalse("a Melody query carries no advertised service UUID", rule.matches(melody))
    }

    @Test
    fun manufacturerData_onlyMatchesAdvertisements() {
        val rule = DeviceMatchRule.ManufacturerData(COMPANY_ID, byteArrayOf(0x01))

        assertTrue(rule.matches(advertisement))
        assertFalse(rule.matches(classic))
        assertFalse(rule.matches(melody))
    }

    @Test
    fun rank_prefersTheHighestPriorityRuleForANeutralInput() {
        val definition = DefinitionJsonCodec.decode(layeredDefinition)

        val match = DefinitionMatcher.rank(definition, classic)

        assertEquals(70, match?.priority)
        assertTrue(DefinitionMatcher.matches(definition, melody))
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        const val COMPANY_ID = 0x004C

        val layeredDefinition = """
            {
              "manifest": {
                "id": "demo.layered",
                "displayName": "Layered",
                "version": "1.0.0",
                "matchers": [
                  { "type": "nameRegex", "value": ".*XM3.*", "priority": 20 },
                  { "type": "namePrefix", "value": "WF-", "priority": 70 }
                ]
              }
            }
        """.trimIndent()
    }
}

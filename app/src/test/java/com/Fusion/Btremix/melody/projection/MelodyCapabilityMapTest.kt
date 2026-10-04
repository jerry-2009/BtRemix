package com.Fusion.Btremix.melody.projection

import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.2 acceptance for the capability table (MELODY_BRIDGE_SPEC §5.4 item 3, decision M3-D7): the table
 * exists and can open a switch, but M3 itself turns on nothing, and the structured cloud-side switches
 * are never synthesized from a Definition.
 */
class MelodyCapabilityMapTest {

    @Test
    fun m3_shipsWithNoEnabledCapabilitySwitch() {
        assertTrue("M3-D7 keeps every switch neutral", MelodyCapabilityMap.ENABLED_IN_M3.isEmpty())
        assertTrue(MelodyCapabilityMap.overrides(batteryDevice()).isEmpty())
    }

    @Test
    fun batteryCapability_canDriveTheBatteryInfoSwitch() {
        val overrides = MelodyCapabilityMap.overrides(
            batteryDevice(),
            enabledKeys = setOf("batteryInfo"),
        )

        assertEquals(JsonValue.NumberValue("1"), overrides["batteryInfo"])
        assertEquals(1, overrides.size)
    }

    @Test
    fun batteryStateWithoutTheCapabilityList_stillCounts() {
        val definition = DefinitionJsonCodec.decode(
            definitionJson(capabilities = emptyList(), states = listOf("\"battery.left\": { \"type\": \"integer\" }")),
        )

        val overrides = MelodyCapabilityMap.overrides(definition, enabledKeys = setOf("batteryInfo"))

        assertEquals(JsonValue.NumberValue("1"), overrides["batteryInfo"])
    }

    @Test
    fun definitionWithoutBattery_neverOpensTheSwitch() {
        val definition = DefinitionJsonCodec.decode(
            definitionJson(capabilities = listOf("\"anc\""), states = listOf("\"ancMode\": { \"type\": \"integer\" }")),
        )

        assertTrue(MelodyCapabilityMap.overrides(definition, enabledKeys = setOf("batteryInfo")).isEmpty())
    }

    @Test
    fun structuredCloudSwitchesAreNotSynthesized() {
        val keys = MelodyCapabilityMap.rules.map { it.functionKey }

        assertEquals(listOf("batteryInfo"), keys)
        listOf("noiseReductionMode", "equalizerMode", "control", "callControl", "multiConnectFunctions")
            .forEach { assertTrue("$it needs host cloud tables and must have no rule", it !in keys) }
    }

    @Test
    fun productType_mapsToTheObservedFormFactorTokens() {
        assertEquals("T1", MelodyProductType.officialType(MelodyProductType.TWS_FIRST_GEN))
        assertEquals("T2", MelodyProductType.officialType(MelodyProductType.TWS_SECOND_GEN))
        assertEquals("N", MelodyProductType.officialType(MelodyProductType.NECKBAND))
        assertEquals("O1", MelodyProductType.officialType(MelodyProductType.CLIP_OPEN))
    }

    @Test
    fun unmappedProductType_staysNeutral() {
        listOf(-1, 0, 5, 99).forEach { assertNull("productType=$it", MelodyProductType.officialType(it)) }
    }

    @Test
    fun mappedTokensStayInsideTheObservedExportSet() {
        listOf(1, 2, 3, 4)
            .mapNotNull(MelodyProductType::officialType)
            .forEach { assertTrue("$it is not an observed content.type", it in MelodyProductType.OBSERVED_TYPES) }
    }

    private fun batteryDevice(): LoadedDeviceDefinition = DefinitionJsonCodec.decode(
        definitionJson(
            capabilities = listOf("\"battery\""),
            states = listOf("\"battery.left\": { \"type\": \"integer\" }"),
        ),
    )

    private fun definitionJson(capabilities: List<String>, states: List<String>): String = """
        {
          "manifest": {
            "id": "test.device",
            "displayName": "Test Device",
            "version": "1.0.0",
            "schemaVersion": 4,
            "capabilities": [${capabilities.joinToString(",")}],
            "matchers": [{ "type": "namePrefix", "value": "Test" }]
          },
          "states": { ${states.joinToString(",")} }
        }
    """.trimIndent()
}

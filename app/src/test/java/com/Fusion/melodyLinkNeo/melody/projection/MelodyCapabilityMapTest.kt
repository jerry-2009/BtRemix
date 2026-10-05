package com.fusion.melodyLinkNeo.melody.projection

import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.definition.api.ActionDefinition
import com.fusion.melodyLinkNeo.definition.api.DefinitionManifest
import com.fusion.melodyLinkNeo.definition.api.StateDefinition
import com.fusion.melodyLinkNeo.definition.api.StateDefinitionType
import com.fusion.melodyLinkNeo.definition.json.DefinitionJsonCodec
import com.fusion.melodyLinkNeo.definition.json.JsonValue
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
        assertTrue(MelodyCapabilityMap.overrides(batteryDevice(), MelodyCapabilityMap.ENABLED_IN_M3).isEmpty())
    }

    @Test
    fun m4_enablesTheSpatialAndNativeAncSwitches() {
        assertEquals(
            setOf("spatialTypes", "noiseReductionMode", "noiseReductionUIVersion"),
            MelodyCapabilityMap.ENABLED_IN_M4,
        )
    }

    @Test
    fun spatialCapability_turnsOnTheSpatialTypesListWithTheObservedShape() {
        val overrides = MelodyCapabilityMap.overrides(spatialDevice())

        assertEquals(
            JsonValue.Array(listOf(JsonValue.NumberValue("0"), JsonValue.NumberValue("1"))),
            overrides["spatialTypes"],
        )
        assertEquals(1, overrides.size)
    }

    @Test
    fun definitionWithoutTheSpatialCapability_neverOpensTheSpatialTypesSwitch() {
        // The official sound group is opt-in: a Definition that did not ask for it keeps the neutral
        // `spatialTypes: null` the template left behind (M4.1 §3).
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

        assertEquals(
            listOf("batteryInfo", "spatialTypes", "noiseReductionMode", "noiseReductionUIVersion"),
            keys,
        )
        listOf("equalizerMode", "control", "callControl", "multiConnectFunctions")
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

    private fun spatialDevice(): LoadedDeviceDefinition = DefinitionJsonCodec.decode(
        definitionJson(
            capabilities = listOf("\"spatial\""),
            states = listOf("\"eqPreset\": { \"type\": \"integer\" }"),
        ),
    )

    // --- M4.3b: native ANC mode table (D-11/D-12) -----------------------------------------------

    @Test
    fun ancEnumState_derivesTheHostModeTableByConvention() {
        val plan = MelodyCapabilityMap.ancPlan(ancDevice())

        assertEquals(1, plan.uiVersion)
        assertEquals(listOf(1, 5, 2, 10), plan.modes.map { it.modeType })
        assertEquals(listOf(0, 1, 2, 3), plan.modes.map { it.protocolIndex })
        assertEquals(listOf("off", "anc", "ambient", "wind"), plan.modes.map { it.state })
        assertEquals("Noise canceling", plan.modes[1].label)
        assertEquals("Wind noise reduction", plan.modes.last().label)
    }

    @Test
    fun declaredAncTable_overridesTheDerivation() {
        val definition = DefinitionJsonCodec.decode(ancDefinitionJson())
        val plan = MelodyCapabilityMap.ancPlan(definition)

        assertEquals(2, plan.uiVersion)
        assertEquals(listOf(5, 1), plan.modes.map { it.modeType })
        assertEquals(listOf(1, 0), plan.modes.map { it.protocolIndex })
    }

    @Test
    fun declaredAncModeWithoutLabel_fallsBackToTheEnumDisplayName() {
        val definition = DefinitionJsonCodec.decode(ancDefinitionJson(withLabels = false))

        val plan = MelodyCapabilityMap.ancPlan(definition)

        assertEquals("Noise canceling", plan.modes[0].label)
        assertEquals("Off", plan.modes[1].label)
    }

    @Test
    fun ancSwitches_carryTheDerivedTableAndUIVersion() {
        val overrides = MelodyCapabilityMap.overrides(
            ancDevice(),
            enabledKeys = setOf("noiseReductionMode", "noiseReductionUIVersion"),
        )

        val modes = (overrides["noiseReductionMode"] as JsonValue.Array).values
            .map { it as JsonValue.Object }
        assertEquals(4, modes.size)
        assertEquals(
            listOf(JsonValue.NumberValue("1"), JsonValue.NumberValue("5"), JsonValue.NumberValue("2"), JsonValue.NumberValue("10")),
            modes.map { it.values["modeType"] },
        )
        modes.forEach {
            assertEquals(JsonValue.BooleanValue(false), it.values["decideByEarDevice"])
        }
        assertEquals(JsonValue.NumberValue("1"), overrides["noiseReductionUIVersion"])
    }

    @Test
    fun definitionWithoutAnAncState_neverOpensTheAncSwitches() {
        val definition = DefinitionJsonCodec.decode(
            definitionJson(
                capabilities = listOf("\"battery\""),
                states = listOf("\"battery.left\": { \"type\": \"integer\" }"),
            ),
        )

        assertTrue(MelodyCapabilityMap.ancPlan(definition).isEmpty)
        assertTrue(
            MelodyCapabilityMap.overrides(
                definition,
                enabledKeys = setOf("noiseReductionMode", "noiseReductionUIVersion"),
            ).isEmpty(),
        )
    }

    @Test
    fun ancStrength_isDerivedFromTheBoundedIntegerStateAndItsResultAction() {
        val strength = requireNotNull(MelodyCapabilityMap.ancPlan(ancDeviceWithLevel()).strength)

        assertEquals("ancLevel", strength.state)
        assertEquals("anc.setLevel", strength.action)
        // Sony's 1..20 collapses to the native「降噪效果」Low / Moderate / High anchors.
        assertEquals(listOf(1, 10, 20), strength.levels.map { it.level })
        assertEquals(listOf(3, 8, 4), strength.levels.map { it.modeType })
    }

    @Test
    fun noiseReductionMode_carriesTheNativeStrengthChildrenOnTheAncEntry() {
        val overrides = MelodyCapabilityMap.overrides(
            ancDeviceWithLevel(),
            enabledKeys = setOf("noiseReductionMode"),
        )

        val modes = (overrides["noiseReductionMode"] as JsonValue.Array).values.map { it as JsonValue.Object }
        val anc = modes.first { it.values["modeType"] == JsonValue.NumberValue("5") }
        val children = (anc.values["childrenMode"] as JsonValue.Array).values.map { it as JsonValue.Object }
        assertEquals(
            listOf(JsonValue.NumberValue("3"), JsonValue.NumberValue("8"), JsonValue.NumberValue("4")),
            children.map { it.values["modeType"] },
        )
        assertEquals(
            listOf(JsonValue.NumberValue("100"), JsonValue.NumberValue("101"), JsonValue.NumberValue("102")),
            children.map { it.values["protocolIndex"] },
        )
        modes.filter { it.values["modeType"] != JsonValue.NumberValue("5") }
            .forEach { assertNull("only the ANC entry owns the strength children", it.values["childrenMode"]) }
    }

    @Test
    fun ancEntryWithoutStrength_carriesNoChildrenMode() {
        val overrides = MelodyCapabilityMap.overrides(ancDevice(), enabledKeys = setOf("noiseReductionMode"))

        val modes = (overrides["noiseReductionMode"] as JsonValue.Array).values.map { it as JsonValue.Object }
        modes.forEach { assertNull(it.values["childrenMode"]) }
    }

    private fun ancDeviceWithLevel(): LoadedDeviceDefinition = LoadedDeviceDefinition(
            manifest = DefinitionManifest(
                id = "test.anc",
                displayName = "Test ANC",
                version = "1.0.0",
                schemaVersion = 4,
                capabilities = setOf("anc"),
            ),
            states = mapOf(
                "ancMode" to StateDefinition(
                    key = "ancMode",
                    type = StateDefinitionType.ENUM,
                    enumValues = mapOf("off" to "Off", "anc" to "Noise canceling"),
                ),
                "ancLevel" to StateDefinition(
                    key = "ancLevel",
                    type = StateDefinitionType.INTEGER,
                    displayName = "Ambient level",
                    min = 1.0,
                    max = 20.0,
                    step = 1.0,
                ),
            ),
            actions = mapOf(
                "anc.setLevel" to ActionDefinition(id = "anc.setLevel", displayName = "Set level", resultState = "ancLevel"),
            ),
        )

    private fun ancDevice(): LoadedDeviceDefinition = DefinitionJsonCodec.decode(
        definitionJson(
            capabilities = listOf("\"anc\""),
            states = listOf(
                "\"ancMode\": { \"type\": \"enum\", \"enumValues\": { " +
                    "\"off\": \"Off\", \"anc\": \"Noise canceling\", " +
                    "\"ambient\": \"Ambient sound\", \"wind\": \"Wind noise reduction\" } }",
            ),
        ),
    )

    private fun ancDefinitionJson(withLabels: Boolean = true): String {
        val label = if (withLabels) { """ "label": "Custom", """ } else { "" }
        return """
            {
              "manifest": {
                "id": "test.device",
                "displayName": "Test Device",
                "version": "1.0.0",
                "schemaVersion": 4,
                "capabilities": ["anc"],
                "matchers": [{ "type": "namePrefix", "value": "Test" }]
              },
              "melody": {
                "support": { "name": "Test Device" },
                "anc": {
                  "uiVersion": 2,
                  "modes": [
                    { $label "modeType": 5, "protocolIndex": 1, "state": "anc" },
                    { "modeType": 1, "protocolIndex": 0, "state": "off" }
                  ]
                }
              },
              "states": {
                "ancMode": { "type": "enum", "enumValues": { "off": "Off", "anc": "Noise canceling" } }
              }
            }
        """.trimIndent()
    }

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

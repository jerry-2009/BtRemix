package com.Fusion.Btremix.melody.projection

import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.json.JsonParser
import com.Fusion.Btremix.definition.json.JsonValue
import com.Fusion.Btremix.definition.json.JsonWriter
import com.Fusion.Btremix.melody.config.MelodyManagedDevice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.2 acceptance: the projection builder hands the host a *legal* envelope - the M3.-1 template's
 * structural field set, the Definition's identity fields, the M4 panel policy, and the M3.2 capability
 * map. M3-D7 means the capability table is present but every switch must still be exactly what the
 * neutral template left it.
 */
class MelodyProjectionBuilderTest {

    private val device = managedDevice()

    @Test
    fun projection_keepsTheTemplateFieldSetAndOverridesIdentity() {
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { templateFile().readText() })

        val envelope = parse(builder.build(device))
        val whitelist = envelope.getValue("whitelist").asObject()

        assertEquals(1.0, requireNotNull(envelope.getValue("version").asNumber()), 0.0)
        assertEquals("14:3F:A6:02:5F:B0", envelope.getValue("mac").asString())
        val definitionNode = envelope.getValue("definition").asObject()
        assertEquals("sony.wf1000xm3", definitionNode.getValue("id").asString())
        assertEquals("1.0.0", definitionNode.getValue("version").asString())

        assertEquals("Sony WF-1000XM3", whitelist.getValue("name").asString())
        assertEquals("Sony", whitelist.getValue("brand").asString())
        assertEquals("000CE0", whitelist.getValue("id").asString())
        assertEquals("T1", whitelist.getValue("type").asString())
        assertEquals("96cc203e-5068-46ad-b32d-e316f5e069ba", whitelist.getValue("uuid").asString())
        assertEquals(false, whitelist.getValue("supportSpp").asBoolean())
        // The template's 135-function object survives intact; M3-D7 leaves it neutral.
        assertEquals(135, whitelist.getValue("function").asObject().size)
    }

    @Test
    fun projection_keepsEveryCapabilitySwitchAtTheTemplateValue() {
        val template = JsonParser.parse(templateFile().readText()).asObject()
        // The M3-D7 neutral envelope is still reachable by asking for the M3 set explicitly.
        val builder = MelodyProjectionBuilder(
            MelodyTemplateSource { templateFile().readText() },
            enabledCapabilities = MelodyCapabilityMap.ENABLED_IN_M3,
        )

        val function = parse(builder.build(device))
            .getValue("whitelist")
            .asObject()
            .getValue("function")

        // Deep equality is the M3-D7 contract: the builder may rearrange nothing in `function`.
        assertEquals(JsonValue.Object(template.getValue("function").asObject()), function)
    }

    @Test
    fun projection_defaultsToTheM4CapabilitySet() {
        val template = JsonParser.parse(templateFile().readText()).asObject()
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { templateFile().readText() })

        val function = parse(builder.build(managedDevice(capabilities = listOf("battery", "spatial"))))
            .getValue("whitelist")
            .asObject()
            .getValue("function")
            .asObject()
        val templateFunction = template.getValue("function").asObject()

        // M4.1: exactly one switch differs from the neutral template, and it is the spatial list.
        assertEquals(
            JsonValue.Array(listOf(JsonValue.NumberValue("0"), JsonValue.NumberValue("1"))),
            function.getValue("spatialTypes"),
        )
        assertEquals(templateFunction.size, function.size)
        function.filterKeys { it != "spatialTypes" }
            .forEach { (key, value) -> assertEquals("$key changed", templateFunction.getValue(key), value) }
    }

    @Test
    fun projection_canOpenAnAllowlistedCapabilitySwitch() {
        val template = JsonParser.parse(templateFile().readText()).asObject()
        val builder = MelodyProjectionBuilder(
            MelodyTemplateSource { templateFile().readText() },
            enabledCapabilities = setOf("batteryInfo"),
        )

        val function = parse(builder.build(device))
            .getValue("whitelist")
            .asObject()
            .getValue("function")
            .asObject()
        val templateFunction = template.getValue("function").asObject()

        assertEquals(1.0, function.getValue("batteryInfo").asNumber()!!, 0.0)
        assertEquals(templateFunction.size, function.size)
        function.filterKeys { it != "batteryInfo" }
            .forEach { (key, value) -> assertEquals("$key changed", templateFunction.getValue(key), value) }
    }

    @Test
    fun projection_debugOverrideWritesTheRawFieldAndWinsOverTheRuleTable() {
        val builder = MelodyProjectionBuilder(
            templates = MelodyTemplateSource { templateFile().readText() },
            enabledCapabilities = emptySet(),
            capabilityOverrides = mapOf(
                "spatialTypes" to JsonParser.parse("[0,1]"),
                "equalizer" to JsonValue.NumberValue("1"),
            ),
        )

        val function = parse(builder.build(device))
            .getValue("whitelist")
            .asObject()
            .getValue("function")
            .asObject()

        assertEquals(
            JsonValue.Array(listOf(JsonValue.NumberValue("0"), JsonValue.NumberValue("1"))),
            function.getValue("spatialTypes"),
        )
        assertEquals(JsonValue.NumberValue("1"), function.getValue("equalizer"))
        // The template field set survives: the override replaces values, it does not add fields.
        assertEquals(135, function.size)
    }

    @Test
    fun projection_withoutAnyTemplate_stillAppliesTheDebugOverride() {
        val builder = MelodyProjectionBuilder(
            templates = MelodyTemplateSource { null },
            capabilityOverrides = mapOf("equalizer" to JsonValue.NumberValue("1")),
        )

        val function = parse(builder.build(device))
            .getValue("whitelist")
            .asObject()
            .getValue("function")
            .asObject()

        assertEquals(setOf("batteryRadix", "equalizer"), function.keys)
    }

    @Test
    fun projection_instanceProductIdOverridesTheDefinition() {
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { null })

        val whitelist = parse(builder.build(device, instanceProductId = "454672"))
            .getValue("whitelist")
            .asObject()

        assertEquals("06F010", whitelist.getValue("id").asString())
    }

    @Test
    fun projection_withoutDeclaredTemplateFallsBackToTheBuiltInAsset() {
        val declared = "assets/melody/does-not-exist.json"
        val builder = MelodyProjectionBuilder(
            MelodyTemplateSource { path ->
                if (path == declared) "not json at all" else templateFile().readText()
            },
        )

        val projection = builder.project(managedDevice(templateWhitelist = declared))
        val whitelist = parse(projection.json).getValue("whitelist").asObject()

        assertEquals(MelodyTemplateUse.FALLBACK, projection.templateUse)
        assertTrue(projection.templateMissing)
        assertTrue(projection.templateFound)
        assertFalse(projection.degraded)
        // The fallback is the full built-in template, so the host still gets all 135 switches.
        assertEquals(135, whitelist.getValue("function").asObject().size)
        assertEquals("Sony WF-1000XM3", whitelist.getValue("name").asString())
    }

    @Test
    fun projection_withTruncatedDeclaredTemplateAlsoFallsBack() {
        val declared = "assets/melody/truncated.json"
        val builder = MelodyProjectionBuilder(
            MelodyTemplateSource { path ->
                if (path == declared) "{ \"function\": { \"batteryInfo\":" else templateFile().readText()
            },
        )

        val projection = builder.project(managedDevice(templateWhitelist = declared))

        assertEquals(MelodyTemplateUse.FALLBACK, projection.templateUse)
        assertTrue(projection.templateFound)
    }

    @Test
    fun projection_withTemplateMissingTheFunctionFieldIsTreatedAsCorrupt() {
        val declared = "assets/melody/no-function.json"
        val builder = MelodyProjectionBuilder(
            MelodyTemplateSource { path ->
                if (path == declared) "{ \"id\": \"06F010\" }" else templateFile().readText()
            },
        )

        val projection = builder.project(managedDevice(templateWhitelist = declared))

        assertEquals(MelodyTemplateUse.FALLBACK, projection.templateUse)
    }

    @Test
    fun projection_carriesThePanelPolicy() {
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { null })

        val envelope = parse(builder.build(device))
        val panel = envelope.getValue("panel").asObject()

        assertEquals("BtRemix", panel.getValue("sectionTitle").asString())
        assertEquals(listOf("ai"), panel.getValue("hideSections").asStrings())
        assertEquals(listOf("pref_game_mode"), panel.getValue("hideKeys").asStrings())
        assertTrue(panel.getValue("greyKeys").asStrings().isEmpty())
    }

    // --- M4.3b: the `anc` node and the native ANC switches ---------------------------------------

    @Test
    fun projection_carriesTheResolvedAncNode() {
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { templateFile().readText() })

        val anc = parse(builder.build(managedAncDevice())).getValue("anc").asObject()

        assertEquals(1.0, anc.getValue("uiVersion").asNumber()!!, 0.0)
        val modes = (anc.getValue("modes") as JsonValue.Array).values.map { it.asObject() }
        assertEquals(listOf(5.0, 1.0, 2.0, 10.0), modes.map { it.getValue("modeType").asNumber() })
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), modes.map { it.getValue("protocolIndex").asNumber() })
        assertEquals("Noise canceling", modes[0].getValue("label").asString())
        val strength = anc.getValue("strength").asObject()
        assertEquals("ancLevel", strength.getValue("state").asString())
        assertEquals("anc.setLevel", strength.getValue("action").asString())
        val levels = (strength.getValue("levels") as JsonValue.Array).values.map { it.asObject() }
        assertEquals(listOf(1.0, 10.0, 20.0), levels.map { it.getValue("level").asNumber() })
        assertEquals(listOf(3.0, 8.0, 4.0), levels.map { it.getValue("modeType").asNumber() })
        assertEquals(listOf(10.0, 11.0, 12.0), levels.map { it.getValue("protocolIndex").asNumber() })
    }

    @Test
    fun projection_opensTheNativeAncSwitchesOnlyForADefinitionWithATable() {
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { templateFile().readText() })

        val function = parse(builder.build(managedAncDevice()))
            .getValue("whitelist").asObject().getValue("function").asObject()

        assertTrue(function.getValue("noiseReductionMode") is JsonValue.Array)
        assertEquals(1.0, function.getValue("noiseReductionUIVersion").asNumber()!!, 0.0)
        // The「降噪效果」route rides on the ANC entry's `childrenMode`, not the `opsReduction` variant.
        assertEquals(0.0, function.getValue("opsReduction").asNumber()!!, 0.0)
        val ancEntry = (function.getValue("noiseReductionMode") as JsonValue.Array).values
            .map { it.asObject() }
            .first { it.getValue("modeType").asNumber() == 5.0 }
        val children = (ancEntry.getValue("childrenMode") as JsonValue.Array).values.map { it.asObject() }
        assertEquals(3, children.size)

        val withoutAnc = parse(builder.build(device))
            .getValue("whitelist").asObject().getValue("function").asObject()
        assertNull(withoutAnc.getValue("noiseReductionMode").asNumber())
    }

    @Test
    fun projection_withoutAnyTemplate_degradesToTheMinimalFieldSet() {
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { null })

        val projection = builder.project(managedDevice(capabilities = listOf("battery")))
        val envelope = parse(projection.json)
        val whitelist = envelope.getValue("whitelist").asObject()

        assertEquals(MelodyTemplateUse.MINIMAL, projection.templateUse)
        assertFalse(projection.templateFound)
        assertTrue(projection.templateMissing)
        assertTrue(projection.degraded)
        assertEquals("Sony WF-1000XM3", whitelist.getValue("name").asString())
        assertEquals("000CE0", whitelist.getValue("id").asString())
        assertEquals("T1", whitelist.getValue("type").asString())
        assertEquals(false, whitelist.getValue("supportSpp").asBoolean())
        // The 19 top-level fields the host expects are always present...
        assertEquals(MINIMAL_FIELDS, whitelist.keys)
        // ...but the capability table is not invented; only the structural parameter survives.
        assertEquals(setOf("batteryRadix"), whitelist.getValue("function").asObject().keys)
        assertEquals(10.0, whitelist.getValue("function").asObject().getValue("batteryRadix").asNumber()!!, 0.0)
        assertNotNull(envelope.getValue("panel"))
    }

    @Test
    fun projection_envelopeSurvivesAJsonRoundTrip() {
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { templateFile().readText() })
        val json = builder.build(device)

        // Compact, order-preserving output: re-writing the parsed tree is byte-identical, which is what
        // the host-side cache's fingerprint comparison relies on.
        assertEquals(json, JsonWriter.write(JsonParser.parse(json)))
        assertEquals(parse(json), parse(JsonWriter.write(JsonParser.parse(json))))
    }

    @Test
    fun projection_unmappedProductTypeKeepsTheTemplateNeutralValue() {
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { templateFile().readText() })

        val whitelist = parse(builder.build(managedDevice(productType = 99)))
            .getValue("whitelist")
            .asObject()

        assertNull(whitelist.getValue("type").asString())
    }

    private fun managedDevice(
        productType: Int = 1,
        templateWhitelist: String? = null,
        capabilities: List<String> = listOf("battery", "anc", "equalizer", "upscaling"),
    ): MelodyManagedDevice {
        val definition = definition(
            productType = productType,
            templateWhitelist = templateWhitelist,
            capabilities = capabilities,
        )
        return MelodyManagedDevice(
            mac = "14:3F:A6:02:5F:B0",
            name = "WF-1000XM3",
            definition = definition,
            melody = requireNotNull(definition.melody),
        )
    }

    private fun managedAncDevice(): MelodyManagedDevice {
        val definition = DefinitionJsonCodec.decode(ancDefinitionJson())
        return MelodyManagedDevice(
            mac = "14:3F:A6:02:5F:B0",
            name = "Test ANC",
            definition = definition,
            melody = requireNotNull(definition.melody),
        )
    }

    private fun ancDefinitionJson(): String = """
        {
          "manifest": {
            "id": "test.anc",
            "displayName": "Test ANC",
            "version": "1.0.0",
            "schemaVersion": 4,
            "capabilities": ["anc"],
            "matchers": [{ "type": "namePrefix", "value": "Test" }]
          },
          "melody": {
            "support": { "name": "Test ANC", "brand": "Test" },
            "anc": {
              "uiVersion": 1,
              "modes": [
                { "modeType": 5, "protocolIndex": 0, "state": "anc", "label": "Noise canceling" },
                { "modeType": 1, "protocolIndex": 1, "state": "off", "label": "Off" },
                { "modeType": 2, "protocolIndex": 2, "state": "ambient", "label": "Ambient sound" },
                { "modeType": 10, "protocolIndex": 3, "state": "wind", "label": "Wind noise reduction" }
              ],
              "strength": { "state": "ancLevel", "action": "anc.setLevel", "levels": [
                { "modeType": 3, "protocolIndex": 10, "level": 1 },
                { "modeType": 8, "protocolIndex": 11, "level": 10 },
                { "modeType": 4, "protocolIndex": 12, "level": 20 }
              ] }
            }
          },
          "states": {
            "ancMode": { "type": "enum", "enumValues": { "off": "Off", "anc": "Noise canceling", "ambient": "Ambient sound", "wind": "Wind noise reduction" } },
            "ancLevel": { "type": "integer", "min": 1, "max": 20, "step": 1 }
          }
        }
    """.trimIndent()

    private fun definition(
        productType: Int = 1,
        templateWhitelist: String? = null,
        capabilities: List<String> = listOf("battery", "anc", "equalizer", "upscaling"),
    ) = DefinitionJsonCodec.decode(definitionJson(productType, templateWhitelist, capabilities))

    private fun parse(text: String): Map<String, JsonValue> =
        JsonParser.parse(text).asObject()

    private fun JsonValue.asObject(): Map<String, JsonValue> =
        (this as? JsonValue.Object)?.values ?: error("expected a JSON object, got $this")

    private fun JsonValue.asString(): String? = (this as? JsonValue.StringValue)?.value

    private fun JsonValue.asBoolean(): Boolean? = (this as? JsonValue.BooleanValue)?.value

    private fun JsonValue.asNumber(): Double? = (this as? JsonValue.NumberValue)?.raw?.toDouble()

    private fun JsonValue.asStrings(): List<String> =
        (this as? JsonValue.Array)?.values?.mapNotNull { (it as? JsonValue.StringValue)?.value }
            ?: error("expected a JSON array, got $this")

    private fun templateFile(): File = CANDIDATES.firstOrNull(File::isFile)
        ?: error("cannot locate melody/whitelist-template.json from ${File("").absolutePath}")

    private companion object {
        private val CANDIDATES = listOf(
            File("src/main/assets/melody/whitelist-template.json"),
            File("app/src/main/assets/melody/whitelist-template.json"),
            File("btremix/app/src/main/assets/melody/whitelist-template.json"),
        )

        /** The 19 top-level fields a real `WhitelistConfigDTO` JSON carries (M3.-1 export). */
        private val MINIMAL_FIELDS = setOf(
            "brand", "btDelayReport", "children", "coreFrom", "defaultColor", "function",
            "fuzzyMatchName", "id", "minRssi", "minVersion", "name", "opsPodsVersion",
            "podsVersion", "protocolType", "rssi", "supportRlmDeviceFunction", "supportSpp",
            "type", "uuid",
        )

        fun definitionJson(
            productType: Int,
            templateWhitelist: String?,
            capabilities: List<String> = listOf("battery", "anc", "equalizer", "upscaling"),
        ): String {
            val templateLine = templateWhitelist
                ?.let { ",\n          \"templateWhitelist\": \"$it\"" }
                .orEmpty()
            val capabilityLine = capabilities.joinToString(", ") { "\"$it\"" }
            return """
                {
                  "manifest": {
                    "id": "sony.wf1000xm3",
                    "displayName": "Sony WF-1000XM3",
                    "version": "1.0.0",
                    "schemaVersion": 4,
                    "capabilities": [$capabilityLine],
                    "matchers": [{ "type": "namePrefix", "value": "WF-1000XM3" }]
                  },
                  "melody": {
                    "support": {
                      "name": "Sony WF-1000XM3",
                      "brand": "Sony",
                      "productId": "0x0CE0",
                      "productType": $productType,
                      "uuid": "96cc203e-5068-46ad-b32d-e316f5e069ba"$templateLine
                    },
                    "panel": {
                      "sectionTitle": "BtRemix",
                      "hideSections": ["ai"],
                      "hideKeys": ["pref_game_mode"]
                    }
                  }
                }
            """.trimIndent()
        }
    }
}

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
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { templateFile().readText() })

        val function = parse(builder.build(device))
            .getValue("whitelist")
            .asObject()
            .getValue("function")

        // Deep equality is the M3-D7 contract: the builder may rearrange nothing in `function`.
        assertEquals(JsonValue.Object(template.getValue("function").asObject()), function)
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

    @Test
    fun projection_withoutAnyTemplate_degradesToTheMinimalFieldSet() {
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { null })

        val projection = builder.project(device)
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
    ): MelodyManagedDevice {
        val definition = definition(productType = productType, templateWhitelist = templateWhitelist)
        return MelodyManagedDevice(
            mac = "14:3F:A6:02:5F:B0",
            name = "WF-1000XM3",
            definition = definition,
            melody = requireNotNull(definition.melody),
        )
    }

    private fun definition(productType: Int = 1, templateWhitelist: String? = null) =
        DefinitionJsonCodec.decode(definitionJson(productType, templateWhitelist))

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

        fun definitionJson(productType: Int, templateWhitelist: String?): String {
            val templateLine = templateWhitelist
                ?.let { ",\n          \"templateWhitelist\": \"$it\"" }
                .orEmpty()
            return """
                {
                  "manifest": {
                    "id": "sony.wf1000xm3",
                    "displayName": "Sony WF-1000XM3",
                    "version": "1.0.0",
                    "schemaVersion": 4,
                    "capabilities": ["battery", "anc", "equalizer", "upscaling"],
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

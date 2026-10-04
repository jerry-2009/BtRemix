package com.Fusion.Btremix.melody.projection

import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.json.JsonParser
import com.Fusion.Btremix.definition.json.JsonValue
import com.Fusion.Btremix.melody.config.MelodyManagedDevice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.1 acceptance needs `resolveProjection` to hand the host a *legal* envelope: the M3.-1 template's
 * structural field set, the Definition's identity fields, and the M4 panel policy. The capability map
 * (which `Function` switches to open) is M3.2, so every switch must stay exactly as the template left it.
 */
class MelodyProjectionBuilderTest {

    private val device = MelodyManagedDevice(
        mac = "14:3F:A6:02:5F:B0",
        name = "WF-1000XM3",
        definition = definition(),
        melody = requireNotNull(definition().melody),
    )

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
        assertEquals("96cc203e-5068-46ad-b32d-e316f5e069ba", whitelist.getValue("uuid").asString())
        assertEquals(false, whitelist.getValue("supportSpp").asBoolean())
        // The template's 135-function object survives intact; M3-D7 leaves it neutral.
        assertEquals(135, whitelist.getValue("function").asObject().size)
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
    fun projection_withoutTemplate_stillBuildsIdentityAndReportsTheGap() {
        val builder = MelodyProjectionBuilder(MelodyTemplateSource { null })

        val projection = builder.project(device)
        val envelope = parse(projection.json)
        val whitelist = envelope.getValue("whitelist").asObject()

        assertFalse(projection.templateFound)
        assertEquals("Sony WF-1000XM3", whitelist.getValue("name").asString())
        assertEquals("000CE0", whitelist.getValue("id").asString())
        assertNotNull(envelope.getValue("panel"))
    }

    private fun definition(): LoadedDeviceDefinition = DefinitionJsonCodec.decode(definitionJson)

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

        val definitionJson = """
            {
              "manifest": {
                "id": "sony.wf1000xm3",
                "displayName": "Sony WF-1000XM3",
                "version": "1.0.0",
                "schemaVersion": 4,
                "matchers": [{ "type": "namePrefix", "value": "WF-1000XM3" }]
              },
              "melody": {
                "support": {
                  "name": "Sony WF-1000XM3",
                  "brand": "Sony",
                  "productId": "0x0CE0",
                  "uuid": "96cc203e-5068-46ad-b32d-e316f5e069ba"
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

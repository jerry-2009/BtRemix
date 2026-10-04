package com.Fusion.Btremix.definition

import com.Fusion.Btremix.definition.api.DefinitionSchema
import com.Fusion.Btremix.definition.api.MelodyPanelDefinition
import com.Fusion.Btremix.definition.api.MelodyProductId
import com.Fusion.Btremix.definition.api.MelodySupportDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.json.DefinitionJsonException
import com.Fusion.Btremix.definition.validator.DefinitionValidationError
import com.Fusion.Btremix.definition.validator.DefinitionValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the version 4 schema gate and the optional `melody` section. */
class MelodySectionSchemaTest {
    @Test
    fun schemaVersionFour_isCurrentAndSupported() {
        assertEquals(4, DefinitionSchema.VERSION_MELODY)
        assertEquals(DefinitionSchema.VERSION_MELODY, DefinitionSchema.CURRENT)
        assertEquals(1..4, DefinitionSchema.SUPPORTED)
    }

    @Test
    fun validMelodySection_isParsedAndNormalized() {
        val definition = DefinitionJsonCodec.decode(melodyDefinition)
        val melody = requireNotNull(definition.melody)

        assertEquals(DefinitionSchema.VERSION_MELODY, definition.manifest.schemaVersion)
        assertEquals(MelodySupportDefinition.MODE_BRIDGE, melody.support.mode)
        assertEquals("Sony WF-1000XM3", melody.support.name)
        assertEquals("Sony", melody.support.brand)
        // 0x06F010 is the hexadecimal `content.id`; the model stores Melody's decimal query form.
        assertEquals("454672", melody.support.productId)
        assertEquals(1, melody.support.productType)
        assertEquals("00001107-D102-11E1-9B23-00025B00A5A5", melody.support.uuid)
        assertEquals(false, melody.support.supportSpp)
        assertEquals(true, melody.support.suppressMelodyTransport)
        assertEquals(MelodySupportDefinition.DEFAULT_TEMPLATE_WHITELIST, melody.support.templateWhitelist)
        assertEquals("BtRemix", melody.panel.sectionTitle)
        assertEquals(listOf("ai", "game"), melody.panel.hideSections)
        assertEquals(listOf("pref_game_mode"), melody.panel.hideKeys)
        assertEquals(listOf("pref_more_setting"), melody.panel.greyKeys)
    }

    @Test
    fun productId_acceptsDecimalHexAndNumericForms() {
        fun productId(raw: String): String? =
            DefinitionJsonCodec.decode(melodyDefinition.replace("\"0x06F010\"", raw)).melody?.support?.productId

        assertEquals("454672", productId("\"454672\""))
        assertEquals("454672", productId("\"06F010\"")) // bare token with hex letters can only be hex
        assertEquals("454672", productId("454672")) // JSON integer
        // A bare all-digit token is decimal, even with leading zeros: the hex form needs its 0x prefix.
        assertEquals("60412", productId("\"060412\""))
    }

    @Test
    fun productId_isOptional() {
        val definition = DefinitionJsonCodec.decode(melodyDefinition.replace("\"productId\": \"0x06F010\",", ""))

        assertNull(definition.melody?.support?.productId)
    }

    @Test
    fun melodySection_requiresSchemaVersionFour() {
        val errors = validationErrors(melodyDefinition.replace("\"schemaVersion\": 4", "\"schemaVersion\": 3"))

        assertTrue(errors.any { it.path == "melody" })
    }

    @Test
    fun missingName_failsDecoding_andBlankName_failsValidation() {
        val failure = parseFailure(melodyDefinition.replace("\"name\": \"Sony WF-1000XM3\",", ""))
        assertTrue(failure.message.orEmpty().contains("melody.support.name"))

        val errors = validationErrors(melodyDefinition.replace("\"Sony WF-1000XM3\"", "\"\""))
        assertTrue(errors.any { it.path == "melody.support.name" })
    }

    @Test
    fun invalidUuid_isRejected() {
        val errors = validationErrors(melodyDefinition.replace("\"00001107-D102-11E1-9B23-00025B00A5A5\"", "\"not-a-uuid\""))

        assertTrue(errors.any { it.path == "melody.support.uuid" })
    }

    @Test
    fun unknownMode_isRejected() {
        val errors = validationErrors(melodyDefinition.replace("\"mode\": \"bridge\"", "\"mode\": \"mirror\""))

        assertTrue(errors.any { it.path == "melody.support.mode" })
    }

    @Test
    fun invalidProductId_isRejected() {
        assertTrue(
            validationErrors(melodyDefinition.replace("\"0x06F010\"", "\"0xZZ\""))
                .any { it.path == "melody.support.productId" },
        )
        assertTrue(
            validationErrors(melodyDefinition.replace("\"0x06F010\"", "\"999999999999\""))
                .any { it.path == "melody.support.productId" },
        )
        assertTrue(
            validationErrors(melodyDefinition.replace("\"0x06F010\"", "\"0x\""))
                .any { it.path == "melody.support.productId" },
        )
    }

    @Test
    fun panelKeyLists_cannotTargetTheBridgeNamespace() {
        val section = validationErrors(melodyDefinition.replace("\"ai\", \"game\"", "\"melody_bridge_sound\""))
        assertTrue(section.any { it.path == "melody.panel.hideSections[0]" })

        val hidden = validationErrors(melodyDefinition.replace("\"pref_game_mode\"", "\"melody_bridge_noise\""))
        assertTrue(hidden.any { it.path == "melody.panel.hideKeys[0]" })

        val greyed = validationErrors(melodyDefinition.replace("\"pref_more_setting\"", "\"melody_bridge_anc\""))
        assertTrue(greyed.any { it.path == "melody.panel.greyKeys[0]" })
    }

    @Test
    fun hideSections_rejectsBlankEntries() {
        val errors = validationErrors(melodyDefinition.replace("\"ai\", \"game\"", "\"ai\", \"\" "))

        assertTrue(errors.any { it.path == "melody.panel.hideSections[1]" })
    }

    @Test
    fun definitionWithoutMelody_keepsTheOldSchemaValid() {
        val definition = DefinitionJsonCodec.decode(streamDefinitionWithoutMelody)

        assertEquals(DefinitionSchema.VERSION_STREAM, definition.manifest.schemaVersion)
        assertNull(definition.melody)
    }

    @Test
    fun productIdHelper_roundTripsBothHostForms() {
        // Values observed in the M3.-1 whitelist export: ears_whitelist.product_id <-> content.id.
        assertEquals("06EC10", MelodyProductId.toHexId(453648))
        assertEquals("06F010", MelodyProductId.toHexId(454672))
        assertEquals("453648", MelodyProductId.normalizeOrNull("0x06EC10"))
        assertEquals("454672", MelodyProductId.normalizeOrNull("06F010"))
        assertEquals("454672", MelodyProductId.normalizeOrNull("  454672  "))
        assertNull(MelodyProductId.normalizeOrNull("0x1FFFFFFFF"))
        assertNull(MelodyProductId.normalizeOrNull("-5"))
    }

    @Test
    fun customKeyPrefix_isTheBridgeNamespace() {
        assertEquals("melody_bridge_", MelodyPanelDefinition.CUSTOM_KEY_PREFIX)
    }

    private fun validationErrors(json: String): List<DefinitionValidationError> =
        DefinitionValidator.validate(DefinitionJsonCodec.decode(json, validate = false))

    private fun parseFailure(json: String): DefinitionJsonException = try {
        DefinitionJsonCodec.decode(json)
        error("Expected a DefinitionJsonException")
    } catch (error: DefinitionJsonException) {
        error
    }

    private companion object {
        val streamDefinitionWithoutMelody = """
            {
              "manifest": {
                "id": "demo.stream",
                "displayName": "Stream",
                "version": "3.0.0",
                "schemaVersion": 3,
                "matchers": [{ "type": "namePrefix", "value": "Stream" }]
              }
            }
        """.trimIndent()

        val melodyDefinition = """
            {
              "manifest": {
                "id": "demo.melody",
                "displayName": "Melody",
                "version": "4.0.0",
                "schemaVersion": 4,
                "matchers": [{ "type": "namePrefix", "value": "Melody" }]
              },
              "melody": {
                "support": {
                  "mode": "bridge",
                  "name": "Sony WF-1000XM3",
                  "brand": "Sony",
                  "productId": "0x06F010",
                  "productType": 1,
                  "uuid": "00001107-D102-11E1-9B23-00025B00A5A5",
                  "supportSpp": false,
                  "suppressMelodyTransport": true,
                  "templateWhitelist": "assets/melody/whitelist-template.json"
                },
                "panel": {
                  "sectionTitle": "BtRemix",
                  "hideSections": ["ai", "game"],
                  "hideKeys": ["pref_game_mode"],
                  "greyKeys": ["pref_more_setting"]
                }
              }
            }
        """.trimIndent()
    }
}

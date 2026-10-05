package com.fusion.melodyLinkNeo.definition

import com.fusion.melodyLinkNeo.definition.api.DefinitionSchema
import com.fusion.melodyLinkNeo.definition.api.MelodyPanelDefinition
import com.fusion.melodyLinkNeo.definition.api.MelodyAncStrengthDefinition
import com.fusion.melodyLinkNeo.definition.api.MelodyAncStrengthLevel
import com.fusion.melodyLinkNeo.definition.api.MelodyAncMode
import com.fusion.melodyLinkNeo.definition.api.MelodyProductId
import com.fusion.melodyLinkNeo.definition.api.MelodySupportDefinition
import com.fusion.melodyLinkNeo.definition.json.DefinitionJsonCodec
import com.fusion.melodyLinkNeo.definition.json.DefinitionJsonException
import com.fusion.melodyLinkNeo.definition.validator.DefinitionValidationError
import com.fusion.melodyLinkNeo.definition.validator.DefinitionValidator
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

    // --- M5.4: `melody.support.hostVersions` (D-21/D-26) -------------------------------------------

    @Test
    fun hostVersions_isOptionalAndParsed() {
        assertNull(DefinitionJsonCodec.decode(melodyDefinition).melody?.support?.hostVersions)

        val declared = DefinitionJsonCodec.decode(
            melodyDefinition.replace(
                "\"name\": \"Sony WF-1000XM3\",",
                "\"name\": \"Sony WF-1000XM3\", \"hostVersions\": \" >=17.6.3  <18 \",",
            ),
        )

        assertEquals(">=17.6.3  <18", declared.melody?.support?.hostVersions)
    }

    @Test
    fun blankHostVersions_isTreatedAsAbsent() {
        val definition = DefinitionJsonCodec.decode(
            melodyDefinition.replace(
                "\"name\": \"Sony WF-1000XM3\",",
                "\"name\": \"Sony WF-1000XM3\", \"hostVersions\": \"   \",",
            ),
        )

        assertNull(definition.melody?.support?.hostVersions)
    }

    @Test
    fun invalidHostVersions_isRejected() {
        val errors = validationErrors(
            melodyDefinition.replace(
                "\"name\": \"Sony WF-1000XM3\",",
                "\"name\": \"Sony WF-1000XM3\", \"hostVersions\": \">=abc\",",
            ),
        )

        assertTrue(errors.any { it.path == "melody.support.hostVersions" })
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

    // --- M4.3b: the `melody.anc` node -------------------------------------------------------------

    @Test
    fun ancNode_isParsedWithItsTableAndStrength() {
        val anc = requireNotNull(DefinitionJsonCodec.decode(ancMelodyDefinition).melody).anc

        assertEquals(2, anc.uiVersion)
        assertEquals(
            listOf(
                MelodyAncMode(modeType = 5, protocolIndex = 1, state = "anc", label = "Noise canceling"),
                MelodyAncMode(modeType = 1, protocolIndex = 0, state = "off"),
            ),
            anc.modes,
        )
        assertEquals(
            MelodyAncStrengthDefinition(
                state = "ancLevel",
                action = "anc.setLevel",
                levels = listOf(
                    MelodyAncStrengthLevel(modeType = 3, protocolIndex = 10, level = 1),
                    MelodyAncStrengthLevel(modeType = 4, protocolIndex = 11, level = 20),
                ),
            ),
            anc.strength,
        )
    }

    @Test
    fun ancNode_isOptionalAndDefaultsToVersionOne() {
        val anc = requireNotNull(DefinitionJsonCodec.decode(melodyDefinition).melody).anc

        assertEquals(1, anc.uiVersion)
        assertTrue(anc.modes.isEmpty())
        assertNull(anc.strength)
    }

    @Test
    fun invalidAncUIVersion_isRejected() {
        val errors = validationErrors(ancMelodyDefinition.replace("\"uiVersion\": 2", "\"uiVersion\": 9"))

        assertTrue(errors.any { it.path == "melody.anc.uiVersion" })
    }

    @Test
    fun duplicateAncModeType_isRejected() {
        val errors = validationErrors(ancMelodyDefinition.replace("\"modeType\": 1", "\"modeType\": 5"))

        assertTrue(errors.any { it.path == "melody.anc.modes[1].modeType" })
    }

    @Test
    fun invalidAncStrengthLevels_areRejected() {
        val unordered = validationErrors(
            ancMelodyDefinition.replace("\"level\": 1 }", "\"level\": 20 }"),
        )
        assertTrue(unordered.any { it.path == "melody.anc.strength.levels[1].level" })

        val empty = validationErrors(
            ancMelodyDefinition.replace(LEVELS_JSON, "[]"),
        )
        assertTrue(empty.any { it.path == "melody.anc.strength.levels" })

        // The host only renders child modeTypes 3/4/7/8 (Ba.r.b), so anything else would be invisible.
        val invisible = validationErrors(ancMelodyDefinition.replace("\"modeType\": 3,", "\"modeType\": 9,"))
        assertTrue(invisible.any { it.path == "melody.anc.strength.levels[0].modeType" })

        // A child protocolIndex shares the parent table's namespace; `0` is already the Off entry.
        val collision = validationErrors(
            ancMelodyDefinition.replace("\"protocolIndex\": 10,", "\"protocolIndex\": 0,"),
        )
        assertTrue(collision.any { it.path == "melody.anc.strength.levels[0].protocolIndex" })

        // `states.ancLevel` is 1..20, so a level above the range can never be matched on the device.
        val outOfRange = validationErrors(
            ancMelodyDefinition.replace("\"level\": 20 }", "\"level\": 25 }"),
        )
        assertTrue(outOfRange.any { it.path == "melody.anc.strength.levels" })
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
        const val LEVELS_JSON =
            "[ { \"modeType\": 3, \"protocolIndex\": 10, \"level\": 1 }, " +
                "{ \"modeType\": 4, \"protocolIndex\": 11, \"level\": 20 } ]"

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

        val ancMelodyDefinition = """
            {
              "manifest": {
                "id": "demo.melody",
                "displayName": "Melody",
                "version": "4.0.0",
                "schemaVersion": 4,
                "matchers": [{ "type": "namePrefix", "value": "Melody" }]
              },
              "melody": {
                "support": { "mode": "bridge", "name": "Sony WF-1000XM3" },
                "anc": {
                  "uiVersion": 2,
                  "modes": [
                    { "modeType": 5, "protocolIndex": 1, "state": "anc", "label": "Noise canceling" },
                    { "modeType": 1, "protocolIndex": 0, "state": "off" }
                  ],
                  "strength": { "state": "ancLevel", "action": "anc.setLevel", "levels": [ { "modeType": 3, "protocolIndex": 10, "level": 1 }, { "modeType": 4, "protocolIndex": 11, "level": 20 } ] }
                }
              },
              "states": {
                "ancLevel": { "type": "integer", "displayName": "Ambient level", "min": 1, "max": 20, "step": 1 }
              }
            }
        """.trimIndent()
    }
}

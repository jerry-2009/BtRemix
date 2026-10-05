package com.Fusion.Btremix.melody.projection

import com.Fusion.Btremix.definition.api.ActionDefinition
import com.Fusion.Btremix.definition.api.DefinitionManifest
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.StateDefinition
import com.Fusion.Btremix.definition.api.StateDefinitionType
import com.Fusion.Btremix.definition.api.UiNode
import com.Fusion.Btremix.definition.api.UiSchema
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.melody.api.MelodyPanelRowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.3c acceptance for the control auto-routing (decision D-8, `HANDOFF_MELODY_M4_PLAN.md` §3 M4.3c):
 * a Definition `ui` node's destination is decided from its state/action domain, containers are
 * transparent, and every node kind maps to the control the spec's §7.3 table names.
 */
class MelodyUiRoutingTest {

    @Test
    fun sonyLikeUi_putsOnlyNonNativeControlsIntoTheAdvancedGroup() {
        val group = requireNotNull(MelodyUiRouting.advancedGroup(sonyLikeDevice(), "高级功能"))

        assertEquals("melody_bridge_advanced", group.key)
        assertEquals("高级功能", group.title)
        // Battery (progress + refresh buttons) -> header; ANC mode/strength/refresh -> native noise.
        assertEquals(listOf(MelodyPanelRowKind.SEGMENTED, MelodyPanelRowKind.SWITCH), group.rows.map { it.kind })
        assertEquals(listOf("melody_bridge_eqPreset", "melody_bridge_upscaling"), group.rows.map { it.key })
        assertEquals(listOf("Equalizer", "DSEE HX upscaling"), group.rows.map { it.title })

        val equalizer = group.rows[0]
        assertEquals("eqPreset", equalizer.state)
        assertEquals("eq.set", equalizer.action)
        assertEquals(SONY_PRESETS, equalizer.options)
        assertEquals(SONY_PRESET_LABELS, equalizer.optionLabels)
        assertFalse(equalizer.unavailable)

        val upscaling = group.rows[1]
        assertEquals("upscaling", upscaling.state)
        assertEquals("upscaling.set", upscaling.action)
        assertFalse(upscaling.unavailable)
    }

    @Test
    fun sectionTitleFallsBackToTheDefinitionDisplayName() {
        val group = requireNotNull(MelodyUiRouting.advancedGroup(sonyLikeDevice(), "   "))

        assertEquals("Sony WF-1000XM3", group.title)
    }

    @Test
    fun nativeDomains_areClassifiedByStateAndAction() {
        val definition = sonyLikeDevice()

        assertEquals(MelodyUiDomain.BATTERY, MelodyCapabilityMap.domainOf(definition, state = "battery.left"))
        assertEquals(MelodyUiDomain.BATTERY, MelodyCapabilityMap.domainOf(definition, action = "battery.refresh"))
        assertEquals(MelodyUiDomain.ANC_MODE, MelodyCapabilityMap.domainOf(definition, state = "ancMode"))
        assertEquals(MelodyUiDomain.ANC_MODE, MelodyCapabilityMap.domainOf(definition, action = "anc.setMode"))
        assertEquals(MelodyUiDomain.ANC_STRENGTH, MelodyCapabilityMap.domainOf(definition, state = "ancLevel"))
        assertEquals(MelodyUiDomain.ANC_STRENGTH, MelodyCapabilityMap.domainOf(definition, action = "anc.setLevel"))
        // The refresh button has no result state; its ANC namespace still keeps it out of the group.
        assertEquals(MelodyUiDomain.ANC_MODE, MelodyCapabilityMap.domainOf(definition, action = "anc.refresh"))
        assertEquals(MelodyUiDomain.ADVANCED, MelodyCapabilityMap.domainOf(definition, state = "eqPreset", action = "eq.set"))
    }

    @Test
    fun definitionWithoutAnAncDomain_routesAncShapedNodesToTheAdvancedGroup() {
        // `ancMode` is a boolean, not an ANC enum, so no mode table is derived: there is no native ANC
        // group and the `anc*` node must become a custom row instead of being swallowed by the domain.
        val definition = DefinitionJsonCodec.decode(
            definitionJson(
                melody = """"melody": { "support": { "name": "No ANC" } },""",
                states = """"ancMode": { "type": "boolean" }""",
                actions = """"anc.setMode": { "displayName": "Set", "resultState": "ancMode" }""",
                ui = """
                    "ui": { "children": [
                      { "type": "switch", "state": "ancMode", "action": "anc.setMode" }
                    ] }
                """.trimIndent(),
            ),
        )

        assertTrue(MelodyCapabilityMap.ancPlan(definition).isEmpty)
        assertEquals(MelodyUiDomain.ADVANCED, MelodyCapabilityMap.domainOf(definition, state = "ancMode"))
        val group = requireNotNull(MelodyUiRouting.advancedGroup(definition, "Advanced"))
        assertEquals(1, group.rows.size)
        assertEquals(MelodyPanelRowKind.SWITCH, group.rows[0].kind)
        assertFalse(group.rows[0].unavailable)
    }

    @Test
    fun undeclaredState_isShownButGreyedOut() {
        // The JSON validator rejects unknown references, so this defensive path is exercised on a
        // directly-built Definition (e.g. a state pruned after validation, or a hand-built package).
        val definition = LoadedDeviceDefinition(
            manifest = DefinitionManifest(
                id = "test.grey",
                displayName = "Grey",
                version = "1.0.0",
                schemaVersion = 4,
                capabilities = emptySet(),
            ),
            states = mapOf(
                "known" to StateDefinition(
                    key = "known",
                    type = StateDefinitionType.BOOLEAN,
                    displayName = "Known",
                ),
            ),
            actions = mapOf(
                "known.set" to ActionDefinition(id = "known.set", displayName = "Set", resultState = "known"),
            ),
            ui = UiSchema(
                children = listOf(
                    UiNode.Switch(state = "known", action = "known.set"),
                    UiNode.Switch(state = "missing", action = "missing.set"),
                ),
            ),
        )

        val group = requireNotNull(MelodyUiRouting.advancedGroup(definition, "Advanced"))
        assertEquals(listOf("melody_bridge_known", "melody_bridge_missing"), group.rows.map { it.key })
        assertFalse(group.rows[0].unavailable)
        assertTrue(group.rows[1].unavailable)
    }

    @Test
    fun everyNodeKindMapsToItsSpecControl() {
        val definition = DefinitionJsonCodec.decode(
            definitionJson(
                melody = """"melody": { "support": { "name": "Kinds" } },""",
                states = """
                    "flag": { "type": "boolean", "displayName": "Flag" },
                    "level": { "type": "integer", "displayName": "Level", "min": 1, "max": 20, "step": 1 },
                    "mode": { "type": "enum", "displayName": "Mode", "enumValues": { "a": "A", "b": "B" } },
                    "text": { "type": "string" }
                """.trimIndent(),
                actions = """
                    "flag.set": { "displayName": "Set", "resultState": "flag" },
                    "mode.set": { "displayName": "Set", "resultState": "mode" },
                    "level.set": { "displayName": "Set", "resultState": "level" },
                    "refresh": { "displayName": "Refresh" }
                """.trimIndent(),
                ui = """
                    "ui": { "children": [
                      { "type": "switch", "state": "flag", "action": "flag.set" },
                      { "type": "segmented", "state": "mode", "action": "mode.set", "options": ["a", "b"] },
                      { "type": "slider", "state": "level", "action": "level.set" },
                      { "type": "value", "state": "level" },
                      { "type": "text", "text": "About", "state": "text" },
                      { "type": "progress", "state": "level" },
                      { "type": "button", "label": "Refresh", "action": "refresh" }
                    ] }
                """.trimIndent(),
            ),
        )

        val rows = requireNotNull(MelodyUiRouting.advancedGroup(definition, "Advanced")).rows
        assertEquals(
            listOf(
                MelodyPanelRowKind.SWITCH,
                MelodyPanelRowKind.SEGMENTED,
                MelodyPanelRowKind.SLIDER,
                MelodyPanelRowKind.VALUE,
                MelodyPanelRowKind.TEXT,
                MelodyPanelRowKind.PROGRESS,
                MelodyPanelRowKind.BUTTON,
            ),
            rows.map { it.kind },
        )
        val slider = rows.first { it.kind == MelodyPanelRowKind.SLIDER }
        assertEquals(1.0, slider.min)
        assertEquals(20.0, slider.max)
        assertEquals(1.0, slider.step)
        val text = rows.first { it.kind == MelodyPanelRowKind.TEXT }
        assertEquals("About", text.title)
        assertEquals("text", text.state)
        val button = rows.first { it.kind == MelodyPanelRowKind.BUTTON }
        assertEquals("Refresh", button.title)
        assertEquals("refresh", button.action)
    }

    @Test
    fun containersAreTransparentAndProduceNoRowsOfTheirOwn() {
        val definition = DefinitionJsonCodec.decode(
            definitionJson(
                melody = """"melody": { "support": { "name": "Nested" } },""",
                states = """"flag": { "type": "boolean", "displayName": "Flag" }""",
                actions = """"flag.set": { "displayName": "Set", "resultState": "flag" }""",
                ui = """
                    "ui": { "children": [
                      { "type": "section", "title": "Ignored", "children": [
                        { "type": "column", "children": [
                          { "type": "switch", "state": "flag", "action": "flag.set" }
                        ] }
                      ] }
                    ] }
                """.trimIndent(),
            ),
        )

        val group = requireNotNull(MelodyUiRouting.advancedGroup(definition, "Advanced"))
        assertEquals(1, group.rows.size)
        assertEquals(MelodyPanelRowKind.SWITCH, group.rows[0].kind)
    }

    @Test
    fun duplicateNodeKeys_getDeterministicSuffixes() {
        val definition = DefinitionJsonCodec.decode(
            definitionJson(
                melody = """"melody": { "support": { "name": "Dup" } },""",
                states = """"text": { "type": "string" }""",
                actions = "",
                ui = """
                    "ui": { "children": [
                      { "type": "text", "text": "Same", "state": "text" },
                      { "type": "text", "text": "Same", "state": "text" }
                    ] }
                """.trimIndent(),
            ),
        )

        val keys = requireNotNull(MelodyUiRouting.advancedGroup(definition, "Advanced")).rows.map { it.key }
        assertEquals(listOf("melody_bridge_text", "melody_bridge_text_2"), keys)
    }

    @Test
    fun uiWithoutAnyNonNativeControl_producesNoGroup() {
        val definition = DefinitionJsonCodec.decode(
            definitionJson(
                melody = """"melody": { "support": { "name": "Native only" } },""",
                states = """"battery.left": { "type": "integer" }""",
                actions = "",
                ui = """
                    "ui": { "children": [
                      { "type": "progress", "state": "battery.left" }
                    ] }
                """.trimIndent(),
            ),
        )

        assertNull(MelodyUiRouting.advancedGroup(definition, "Advanced"))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun sonyLikeDevice(): LoadedDeviceDefinition = DefinitionJsonCodec.decode(
        definitionJson(
            melody = SONY_MELODY,
            states = SONY_STATES,
            actions = SONY_ACTIONS,
            ui = SONY_UI,
        ),
    )

    private fun definitionJson(
        melody: String,
        states: String,
        actions: String,
        ui: String,
    ): String {
        val actionsBlock = if (actions.isBlank()) "" else """"actions": { $actions },"""
        return """
            {
              "manifest": {
                "id": "test.routing",
                "displayName": "Sony WF-1000XM3",
                "version": "1.0.0",
                "schemaVersion": 4,
                "matchers": [{ "type": "namePrefix", "value": "Test" }]
              },
              $melody
              "states": { $states },
              $actionsBlock
              $ui
            }
        """.trimIndent()
    }

    private companion object {
        private val SONY_PRESETS = listOf("off", "bright", "bass")
        private val SONY_PRESET_LABELS = listOf("Off", "Bright", "Bass boost")

        private val SONY_MELODY = """
            "melody": {
              "support": { "name": "Sony WF-1000XM3" },
              "panel": { "sectionTitle": "高级功能" },
              "anc": {
                "uiVersion": 1,
                "modes": [
                  { "modeType": 5,  "protocolIndex": 0, "state": "anc",     "label": "Noise canceling" },
                  { "modeType": 1,  "protocolIndex": 1, "state": "off",     "label": "Off" },
                  { "modeType": 2,  "protocolIndex": 2, "state": "ambient", "label": "Ambient sound" },
                  { "modeType": 10, "protocolIndex": 3, "state": "wind",    "label": "Wind noise reduction" }
                ],
                "strength": {
                  "state": "ancLevel", "action": "anc.setLevel",
                  "levels": [
                    { "modeType": 3, "protocolIndex": 10, "level": 1 },
                    { "modeType": 8, "protocolIndex": 11, "level": 10 },
                    { "modeType": 4, "protocolIndex": 12, "level": 20 }
                  ]
                }
              }
            },
        """.trimIndent()

        private val SONY_STATES = """
            "battery.left": { "type": "integer", "displayName": "Left earbud", "unit": "%", "min": 0, "max": 100, "step": 1 },
            "battery.right": { "type": "integer", "displayName": "Right earbud", "unit": "%", "min": 0, "max": 100, "step": 1 },
            "battery.case": { "type": "integer", "displayName": "Charging case", "unit": "%", "min": 0, "max": 100, "step": 1 },
            "ancMode": { "type": "enum", "displayName": "Noise control", "enumValues": { "off": "Off", "anc": "Noise canceling", "ambient": "Ambient sound", "wind": "Wind noise reduction" } },
            "ancLevel": { "type": "integer", "displayName": "Ambient level", "min": 1, "max": 20, "step": 1 },
            "eqPreset": { "type": "enum", "displayName": "Equalizer", "enumValues": { "off": "Off", "bright": "Bright", "bass": "Bass boost" } },
            "upscaling": { "type": "boolean", "displayName": "DSEE HX upscaling" }
        """.trimIndent()

        private val SONY_ACTIONS = """
            "battery.refresh": { "displayName": "Refresh earbud battery" },
            "battery.case.refresh": { "displayName": "Refresh case battery" },
            "anc.refresh": { "displayName": "Refresh noise control" },
            "anc.setMode": { "displayName": "Set noise control", "resultState": "ancMode" },
            "anc.setLevel": { "displayName": "Set ambient level", "resultState": "ancLevel" },
            "eq.set": { "displayName": "Set equalizer", "resultState": "eqPreset" },
            "upscaling.set": { "displayName": "Set DSEE HX upscaling", "resultState": "upscaling" }
        """.trimIndent()

        private val SONY_UI = """
            "ui": { "children": [
              { "type": "section", "title": "Battery", "children": [
                { "type": "progress", "state": "battery.left" },
                { "type": "progress", "state": "battery.right" },
                { "type": "progress", "state": "battery.case" },
                { "type": "button", "label": "Refresh earbud battery", "action": "battery.refresh" },
                { "type": "button", "label": "Refresh case battery", "action": "battery.case.refresh" }
              ]},
              { "type": "section", "title": "Noise control", "children": [
                { "type": "segmented", "state": "ancMode", "action": "anc.setMode", "options": ["off", "anc", "ambient", "wind"] },
                { "type": "slider", "state": "ancLevel", "action": "anc.setLevel" },
                { "type": "button", "label": "Refresh noise control", "action": "anc.refresh" }
              ]},
              { "type": "section", "title": "Sound", "children": [
                { "type": "segmented", "state": "eqPreset", "action": "eq.set", "options": ["off", "bright", "bass"] },
                { "type": "switch", "state": "upscaling", "action": "upscaling.set" }
              ]}
            ] }
        """.trimIndent()
    }
}

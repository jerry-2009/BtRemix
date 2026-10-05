package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.melody.config.MelodyManagedDevice
import com.Fusion.Btremix.melody.projection.MelodyProjectionBuilder
import com.Fusion.Btremix.melody.projection.MelodyTemplateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.3c acceptance for the `panel.group` node: the「高级功能」row descriptors survive the envelope
 * round trip and every malformed shape degrades to "insert nothing" without disabling the M4.2
 * hide/grey policy (fail-open, `HANDOFF_MELODY_M4_PLAN.md` §3 M4.3c).
 */
class MelodyPanelGroupTest {

    @Test
    fun panelOf_readsTheGroupRows() {
        val policy = MelodyProviderMerge.panelOf(ROUND_TRIP_ENVELOPE, fallbackTitle = "ignored")

        assertTrue(policy.present)
        val group = requireNotNull(policy.group)
        assertEquals(MelodyPanelGroup.ADVANCED_KEY, group.key)
        assertEquals("高级功能", group.title)
        assertEquals(2, group.rows.size)

        val equalizer = group.rows[0]
        assertEquals(MelodyPanelRowKind.SEGMENTED, equalizer.kind)
        assertEquals("melody_bridge_eqPreset", equalizer.key)
        assertEquals("eqPreset", equalizer.state)
        assertEquals("eq.set", equalizer.action)
        assertEquals(listOf("off", "bright"), equalizer.options)
        assertEquals(listOf("Off", "Bright"), equalizer.optionLabels)

        val upscaling = group.rows[1]
        assertEquals(MelodyPanelRowKind.SWITCH, upscaling.kind)
        assertEquals("melody_bridge_upscaling", upscaling.key)
        assertFalse(upscaling.unavailable)
    }

    @Test
    fun panelOf_keepsTheHidePolicyWhenTheGroupIsMalformed() {
        // A group is additive: dropping it must not degrade the M4.2 policy that rides in the same node.
        val malformedGroups = listOf(
            """"group": "nope"""",
            """"group": { "title": "T", "rows": [] }""",
            """"group": { "key": "official_key", "title": "T", "rows": [{ "kind": "switch", "key": "official", "title": "X" }] }""",
            """"group": { "key": "melody_bridge_advanced", "title": "T", "rows": [{ "kind": "bogus", "key": "melody_bridge_x", "title": "X" }] }""",
            """"group": { "key": "melody_bridge_advanced", "title": "T", "rows": [{ "kind": "switch", "key": "official_key", "title": "X" }] }""",
            """"group": { "key": "melody_bridge_advanced", "title": "T", "rows": [{ "kind": "switch", "key": "melody_bridge_x" }] }""",
        )

        for (body in malformedGroups) {
            val policy = MelodyProviderMerge.panelOf(
                envelope(""""panel": { "sectionTitle": "高级功能", "hideKeys": ["pref_game_mode"], $body }"""),
                fallbackTitle = "Fallback",
            )

            assertTrue("the policy must stay present for $body", policy.present)
            assertEquals(setOf("pref_game_mode"), policy.hideKeys)
            assertNull("the malformed group must be dropped for $body", policy.group)
        }
    }

    @Test
    fun panelOf_hasNoGroupWhenTheNodeIsAbsent() {
        val policy = MelodyProviderMerge.panelOf(
            envelope(""""panel": { "sectionTitle": "高级功能", "hideKeys": [] }"""),
            fallbackTitle = "Fallback",
        )

        assertTrue(policy.present)
        assertNull(policy.group)
    }

    @Test
    fun panelOf_roundTripsWhatTheProjectionBuilderWrote() {
        val definition = DefinitionJsonCodec.decode(SONY_LIKE_DEFINITION)
        val device = MelodyManagedDevice(
            mac = "14:3F:A6:02:5F:B0",
            name = "WF-1000XM3",
            definition = definition,
            melody = requireNotNull(definition.melody),
        )
        val envelope = MelodyProjectionBuilder(MelodyTemplateSource { null }).build(device)

        val policy = MelodyProviderMerge.panelOf(envelope, fallbackTitle = "ignored")
        val group = requireNotNull(policy.group)

        assertEquals("高级功能", group.title)
        assertEquals(listOf("melody_bridge_eqPreset", "melody_bridge_upscaling"), group.rows.map { it.key })
        assertEquals(
            listOf(MelodyPanelRowKind.SEGMENTED, MelodyPanelRowKind.SWITCH),
            group.rows.map { it.kind },
        )
        // The battery and ANC nodes were routed away, so only the two「高级功能」rows travel.
        assertEquals(2, group.rows.size)
    }

    @Test
    fun rowKind_wireRoundTripAndUnknownToken() {
        for (kind in MelodyPanelRowKind.entries) {
            assertEquals(kind, MelodyPanelRowKind.fromWire(kind.wire))
        }
        assertNull(MelodyPanelRowKind.fromWire("bogus"))
        assertNull(MelodyPanelRowKind.fromWire(null))
    }

    @Test
    fun customKeyNamespaceIsRecognised() {
        assertTrue(MelodyPanelGroup.isCustomKey("melody_bridge_advanced"))
        assertFalse(MelodyPanelGroup.isCustomKey("pref_more_setting"))
    }

    private fun envelope(member: String): String = """{ "version": 1, "mac": "AA:BB", $member }"""

    private companion object {
        private val ROUND_TRIP_ENVELOPE = """
            {
              "version": 1,
              "mac": "AA:BB:CC:DD:EE:FF",
              "panel": {
                "sectionTitle": "高级功能",
                "hideKeys": [],
                "group": {
                  "key": "melody_bridge_advanced",
                  "title": "高级功能",
                  "rows": [
                    {
                      "kind": "segmented",
                      "key": "melody_bridge_eqPreset",
                      "title": "Equalizer",
                      "state": "eqPreset",
                      "action": "eq.set",
                      "options": ["off", "bright"],
                      "labels": ["Off", "Bright"]
                    },
                    {
                      "kind": "switch",
                      "key": "melody_bridge_upscaling",
                      "title": "DSEE HX upscaling",
                      "state": "upscaling",
                      "action": "upscaling.set"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        private val SONY_LIKE_DEFINITION = """
            {
              "manifest": {
                "id": "test.sony",
                "displayName": "Sony WF-1000XM3",
                "version": "1.0.0",
                "schemaVersion": 4,
                "matchers": [{ "type": "namePrefix", "value": "Sony" }]
              },
              "melody": {
                "support": { "name": "Sony WF-1000XM3" },
                "panel": { "sectionTitle": "高级功能" },
                "anc": {
                  "uiVersion": 1,
                  "modes": [
                    { "modeType": 5, "protocolIndex": 0, "state": "anc", "label": "Noise canceling" },
                    { "modeType": 1, "protocolIndex": 1, "state": "off", "label": "Off" }
                  ]
                }
              },
              "states": {
                "battery.left": { "type": "integer", "unit": "%" },
                "ancMode": { "type": "enum", "enumValues": { "off": "Off", "anc": "Noise canceling" } },
                "eqPreset": { "type": "enum", "enumValues": { "off": "Off", "bright": "Bright" } },
                "upscaling": { "type": "boolean" }
              },
              "actions": {
                "anc.setMode": { "displayName": "Set noise control", "resultState": "ancMode" },
                "eq.set": { "displayName": "Set equalizer", "resultState": "eqPreset" },
                "upscaling.set": { "displayName": "Set upscaling", "resultState": "upscaling" }
              },
              "ui": {
                "children": [
                  { "type": "progress", "state": "battery.left" },
                  { "type": "segmented", "state": "ancMode", "action": "anc.setMode", "options": ["off", "anc"] },
                  { "type": "segmented", "state": "eqPreset", "action": "eq.set", "options": ["off", "bright"] },
                  { "type": "switch", "state": "upscaling", "action": "upscaling.set" }
                ]
              }
            }
        """.trimIndent()
    }
}

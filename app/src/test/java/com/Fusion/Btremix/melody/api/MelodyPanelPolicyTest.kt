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
 * M4.0 acceptance: the `melody.panel` contract is frozen in one pure function, and every degradation
 * path is fail-open (HANDOFF_MELODY_M4_PLAN.md §3 M4.0 A-2/A-3).
 */
class MelodyPanelPolicyTest {

    @Test
    fun panelOf_readsAWellFormedPolicy() {
        val policy = MelodyProviderMerge.panelOf(
            envelope(
                """
                "panel": {
                  "sectionTitle": "BtRemix",
                  "hideSections": ["devices", "noise", "other"],
                  "hideKeys": ["pref_more_setting", "pref_more_setting"],
                  "greyKeys": ["pref_earphone_setting"]
                }
                """,
            ),
            fallbackTitle = "Fallback",
        )

        assertTrue(policy.present)
        assertNull(policy.missingReason)
        assertEquals("BtRemix", policy.sectionTitle)
        assertEquals(setOf("devices", "noise", "other"), policy.hideSections)
        // Duplicates collapse: membership is all the hook needs.
        assertEquals(setOf("pref_more_setting"), policy.hideKeys)
        assertEquals(setOf("pref_earphone_setting"), policy.greyKeys)
    }

    @Test
    fun panelOf_isInertWhenTheEnvelopeIsMissingOrNotAnObject() {
        for (json in listOf(null, "{ not json", "[1, 2, 3]", "\"text\"")) {
            val policy = MelodyProviderMerge.panelOf(json, fallbackTitle = "Fallback")

            assertFalse("expected degradation for $json", policy.present)
            assertEquals(MelodyPanelPolicy.MISSING_ENVELOPE, policy.missingReason)
            assertEquals("Fallback", policy.sectionTitle)
            assertInert(policy)
        }
    }

    @Test
    fun panelOf_isInertWhenThePanelNodeIsMissingOrMalformed() {
        for (json in listOf("{}", """{"version":1}""", """{"panel":[]}""", """{"panel":"x"}""")) {
            val policy = MelodyProviderMerge.panelOf(json, fallbackTitle = "Fallback")

            assertFalse("expected degradation for $json", policy.present)
            assertEquals(MelodyPanelPolicy.MISSING_NODE, policy.missingReason)
            assertInert(policy)
        }
    }

    @Test
    fun panelOf_isInertWhenTheTitleIsNotAString() {
        val policy = MelodyProviderMerge.panelOf(
            envelope(""""panel": { "sectionTitle": 7, "hideKeys": ["pref_more_setting"] }"""),
            fallbackTitle = "Fallback",
        )

        assertFalse(policy.present)
        assertEquals(MelodyPanelPolicy.MISSING_NODE, policy.missingReason)
        assertInert(policy)
    }

    @Test
    fun panelOf_fallsBackToTheTitleWhenTheSectionTitleIsMissingOrBlank() {
        val missing = MelodyProviderMerge.panelOf(
            envelope(""""panel": { "hideKeys": ["pref_more_setting"] }"""),
            fallbackTitle = "Sony WF-1000XM3",
        )
        val blank = MelodyProviderMerge.panelOf(
            envelope(""""panel": { "sectionTitle": "   " }"""),
            fallbackTitle = "Sony WF-1000XM3",
        )

        assertTrue(missing.present)
        assertEquals("Sony WF-1000XM3", missing.sectionTitle)
        assertEquals(setOf("pref_more_setting"), missing.hideKeys)
        assertTrue(blank.present)
        assertEquals("Sony WF-1000XM3", blank.sectionTitle)
    }

    @Test
    fun panelOf_isInertWhenAKeyFieldIsNotAStringArray() {
        val cases = listOf(
            MelodyPanelPolicy.FIELD_HIDE_SECTIONS to """"hideSections": "other"""",
            MelodyPanelPolicy.FIELD_HIDE_SECTIONS to """"hideSections": ["devices", 7]""",
            MelodyPanelPolicy.FIELD_HIDE_KEYS to """"hideKeys": {}""",
            MelodyPanelPolicy.FIELD_HIDE_KEYS to """"hideKeys": ["pref_more_setting", null]""",
            MelodyPanelPolicy.FIELD_GREY_KEYS to """"greyKeys": 3""",
        )

        for ((expectedReason, body) in cases) {
            val policy = MelodyProviderMerge.panelOf(envelope(""""panel": { $body }"""), "Fallback")

            assertFalse("expected degradation for $body", policy.present)
            assertEquals(expectedReason, policy.missingReason)
            assertInert(policy)
        }
    }

    @Test
    fun panelOf_isInertWhenAKeyIsBlankOrTargetsTheBridgeNamespace() {
        val cases = listOf(
            Triple(MelodyPanelPolicy.FIELD_HIDE_SECTIONS, "hideSections", """["devices", ""]"""),
            Triple(MelodyPanelPolicy.FIELD_HIDE_SECTIONS, "hideSections", """["melody_bridge_section"]"""),
            Triple(MelodyPanelPolicy.FIELD_HIDE_KEYS, "hideKeys", """["melody_bridge_anc"]"""),
            Triple(MelodyPanelPolicy.FIELD_GREY_KEYS, "greyKeys", """["pref_earphone_setting", "melody_bridge_noise"]"""),
        )

        for ((expectedReason, field, array) in cases) {
            val policy = MelodyProviderMerge.panelOf(envelope(""""panel": { "$field": $array }"""), "Fallback")

            assertFalse("expected degradation for $array", policy.present)
            assertEquals(expectedReason, policy.missingReason)
            assertInert(policy)
        }
    }

    @Test
    fun panelOf_treatsAbsentFieldsAsEmptyRatherThanDegraded() {
        val policy = MelodyProviderMerge.panelOf(
            envelope(""""panel": { "sectionTitle": "BtRemix" }"""),
            fallbackTitle = "Fallback",
        )

        assertTrue(policy.present)
        assertEquals("BtRemix", policy.sectionTitle)
        assertTrue(policy.hideSections.isEmpty())
        assertTrue(policy.hideKeys.isEmpty())
        assertTrue(policy.greyKeys.isEmpty())
    }

    @Test
    fun panelOf_roundTripsWhatTheProjectionBuilderWrote() {
        val definition = DefinitionJsonCodec.decode(definitionJson)
        val device = MelodyManagedDevice(
            mac = "14:3F:A6:02:5F:B0",
            name = "WF-1000XM3",
            definition = definition,
            melody = requireNotNull(definition.melody),
        )
        val envelope = MelodyProjectionBuilder(MelodyTemplateSource { null }).build(device)

        val policy = MelodyProviderMerge.panelOf(envelope, fallbackTitle = "ignored")

        assertTrue(policy.present)
        assertEquals("BtRemix", policy.sectionTitle)
        assertEquals(setOf("devices", "other"), policy.hideSections)
        assertEquals(setOf("pref_more_setting"), policy.hideKeys)
        assertEquals(setOf("pref_earphone_setting"), policy.greyKeys)
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun assertInert(policy: MelodyPanelPolicy) {
        assertTrue(policy.hideSections.isEmpty())
        assertTrue(policy.hideKeys.isEmpty())
        assertTrue(policy.greyKeys.isEmpty())
    }

    /** Wraps [member] (with its key) into a minimal envelope that carries only the panel node. */
    private fun envelope(member: String): String = """{ "version": 1, "mac": "AA:BB", $member }"""

    private val definitionJson = """
        {
          "manifest": {
            "id": "demo.melody",
            "displayName": "Demo",
            "version": "1.0.0",
            "schemaVersion": 4,
            "matchers": [{ "type": "namePrefix", "value": "Demo" }]
          },
          "melody": {
            "support": { "name": "Demo" },
            "panel": {
              "sectionTitle": "BtRemix",
              "hideSections": ["devices", "other"],
              "hideKeys": ["pref_more_setting"],
              "greyKeys": ["pref_earphone_setting"]
            }
          }
        }
    """.trimIndent()
}

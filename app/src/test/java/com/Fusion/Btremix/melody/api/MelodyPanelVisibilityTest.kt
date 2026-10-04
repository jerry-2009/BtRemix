package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.definition.api.MelodyPanelDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.2 acceptance for the pure hide/grey applier (HANDOFF_MELODY_M4_PLAN.md §3 M4.2, spec D5).
 *
 * The rule order, the `melody_bridge_*` guard and the empty-group collapse are all verified here with
 * fake rows, so the only thing left for the device run is the reflection wiring in the hook.
 */
class MelodyPanelVisibilityTest {

    @Test
    fun apply_hidesSections_hidesKeys_andGreysKeys() {
        val earphone = row("earphone", group = true)
        val firmware = row("pref_firmware")
        val moreSettings = row("pref_more_setting")
        earphone.add(firmware, moreSettings)
        val sound = row("sound", group = true).add(row("pref_spatial_audio"))
        val screen = listOf(earphone, sound)

        val result = PanelVisibilityApplier.apply(policy(hideSections = setOf("earphone"), greyKeys = setOf("pref_firmware")), screen)

        assertFalse(earphone.isVisible)
        assertEquals(listOf("earphone"), result.hiddenSections)
        // A greyed row stays visible; the row-level hide list was empty here.
        assertTrue(firmware.isVisible)
        assertFalse(firmware.isEnabled)
        assertEquals(listOf("pref_firmware"), result.greyedKeys)
        assertTrue(sound.isVisible)
    }

    @Test
    fun apply_hidesRowsNamedByHideKeys() {
        val sound = row("sound", group = true)
        val equalizer = row("pref_equalizer")
        val spatial = row("pref_spatial_audio")
        sound.add(equalizer, spatial)
        val screen = listOf(sound)

        val result = PanelVisibilityApplier.apply(policy(hideKeys = setOf("pref_equalizer")), screen)

        assertFalse(equalizer.isVisible)
        assertEquals(listOf("pref_equalizer"), result.hiddenKeys)
        assertTrue(spatial.isVisible)
        // The group still has a visible child, so the empty cleanup must leave it alone.
        assertTrue(sound.isVisible)
        assertTrue(result.hiddenEmptySections.isEmpty())
    }

    @Test
    fun apply_collapsesAGroupWhoseChildrenAllBecameInvisible() {
        val sound = row("sound", group = true)
        sound.add(row("pref_equalizer"), row("pref_high_quality_audio"))
        val screen = listOf(sound)

        val result = PanelVisibilityApplier.apply(
            policy(hideKeys = setOf("pref_equalizer", "pref_high_quality_audio")),
            screen,
        )

        assertFalse(sound.isVisible)
        assertEquals(listOf("sound"), result.hiddenEmptySections)
    }

    @Test
    fun apply_collapsesNestedEmptyGroupsBottomUp() {
        val inner = row("inner", group = true).add(row("pref_a"))
        val outer = row("outer", group = true).add(inner)
        val screen = listOf(outer)

        val result = PanelVisibilityApplier.apply(policy(hideKeys = setOf("pref_a")), screen)

        assertFalse(inner.isVisible)
        assertFalse(outer.isVisible)
        assertEquals(listOf("inner", "outer"), result.hiddenEmptySections)
    }

    @Test
    fun apply_doesNotCollapseAGroupWithNoEnumerableChildren() {
        // A COUI category whose children are not reachable through reflection must not be hidden by
        // accident: "no children" is not the same as "all children invisible".
        val opaque = row("sound", group = true)
        val screen = listOf(opaque)

        val result = PanelVisibilityApplier.apply(policy(hideKeys = setOf("pref_equalizer")), screen)

        assertTrue(opaque.isVisible)
        assertTrue(result.isEmpty)
    }

    @Test
    fun apply_prefersHidingOverGreyingWhenAKeyIsInBothLists() {
        val equalizer = row("pref_equalizer")
        val screen = listOf(equalizer)

        val result = PanelVisibilityApplier.apply(
            policy(hideKeys = setOf("pref_equalizer"), greyKeys = setOf("pref_equalizer")),
            screen,
        )

        assertFalse(equalizer.isVisible)
        assertTrue(equalizer.isEnabled)
        assertEquals(listOf("pref_equalizer"), result.hiddenKeys)
        assertTrue(result.greyedKeys.isEmpty())
    }

    @Test
    fun apply_neverTouchesTheBridgeNamespace() {
        val custom = row("melody_bridge_anc")
        val customSection = row("melody_bridge_section", group = true).add(row("melody_bridge_child"))
        val screen = listOf(custom, customSection)

        val result = PanelVisibilityApplier.apply(
            policy(
                hideSections = setOf(MelodyPanelDefinition.CUSTOM_KEY_PREFIX + "section"),
                hideKeys = setOf(MelodyPanelDefinition.CUSTOM_KEY_PREFIX + "anc"),
                greyKeys = setOf(MelodyPanelDefinition.CUSTOM_KEY_PREFIX + "child"),
            ),
            screen,
        )

        assertTrue(custom.isVisible)
        assertTrue(custom.isEnabled)
        assertTrue(customSection.isVisible)
        assertTrue(result.isEmpty)
    }

    @Test
    fun apply_isInertForADegradedPolicy() {
        val row = row("earphone", group = true)
        val degraded = MelodyPanelPolicy.missing("BtRemix", MelodyPanelPolicy.MISSING_ENVELOPE)

        val result = PanelVisibilityApplier.apply(degraded, listOf(row))

        assertEquals(MelodyPanelApplyResult.NONE, result)
        assertTrue(row.isVisible)
        assertEquals(0, row.visibleWrites)
    }

    @Test
    fun apply_isIdempotentAcrossRepeatedCalls() {
        val earphone = row("earphone", group = true)
        val equalizer = row("pref_equalizer")
        val screen = listOf(earphone, equalizer)
        val plan = policy(hideSections = setOf("earphone"), greyKeys = setOf("pref_equalizer"))

        val first = PanelVisibilityApplier.apply(plan, screen)
        val second = PanelVisibilityApplier.apply(plan, screen)

        assertEquals(2, first.changeCount)
        assertTrue(second.isEmpty)
        // The second pass must not write again: no rebuild of the host row was needed.
        assertEquals(1, earphone.visibleWrites)
        assertEquals(1, equalizer.enabledWrites)
    }

    @Test
    fun apply_reportsNothingWhenTheRowIsAlreadyHidden() {
        val devices = row("devices", group = true, visible = false)

        val result = PanelVisibilityApplier.apply(policy(hideSections = setOf("devices")), listOf(devices))

        assertTrue(result.isEmpty)
        assertEquals(0, devices.visibleWrites)
    }

    @Test
    fun apply_ignoresRowsWithoutAKey() {
        val keyless = row("")

        val result = PanelVisibilityApplier.apply(policy(hideKeys = setOf("pref_equalizer")), listOf(keyless))

        assertTrue(result.isEmpty)
        assertTrue(keyless.isVisible)
    }

    @Test
    fun apply_returnsNoneWhenNoRuleMatches() {
        val sound = row("sound", group = true).add(row("pref_spatial_audio"))

        val result = PanelVisibilityApplier.apply(policy(hideSections = setOf("earphone")), listOf(sound))

        assertTrue(result.isEmpty)
        assertEquals(0, result.changeCount)
        assertTrue(sound.isVisible)
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun policy(
        hideSections: Set<String> = emptySet(),
        hideKeys: Set<String> = emptySet(),
        greyKeys: Set<String> = emptySet(),
    ): MelodyPanelPolicy = MelodyPanelPolicy(
        sectionTitle = "BtRemix",
        hideSections = hideSections,
        hideKeys = hideKeys,
        greyKeys = greyKeys,
    )

    private fun row(
        key: String,
        group: Boolean = false,
        visible: Boolean = true,
        enabled: Boolean = true,
    ): FakeRow = FakeRow(key, group, visible, enabled)

    /** Minimal [PanelRow] whose writes are counted, so idempotency is observable. */
    private class FakeRow(
        override val key: String,
        override val isGroup: Boolean,
        visible: Boolean,
        enabled: Boolean,
    ) : PanelRow {
        private val children = mutableListOf<FakeRow>()
        private var visibleState = visible
        private var enabledState = enabled
        var visibleWrites = 0
            private set
        var enabledWrites = 0
            private set

        override val isVisible: Boolean get() = visibleState
        override val isEnabled: Boolean get() = enabledState

        override fun children(): List<PanelRow> = children

        override fun setVisible(visible: Boolean) {
            visibleState = visible
            visibleWrites++
        }

        override fun setEnabled(enabled: Boolean) {
            enabledState = enabled
            enabledWrites++
        }

        fun add(vararg rows: FakeRow): FakeRow = apply { children += rows }
    }
}

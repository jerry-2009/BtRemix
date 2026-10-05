package com.Fusion.Btremix.melody.hook.injection

import com.Fusion.Btremix.melody.api.MelodyPanelGroup
import com.Fusion.Btremix.melody.api.MelodyPanelPolicy
import com.Fusion.Btremix.melody.api.MelodyPanelRow
import com.Fusion.Btremix.melody.api.MelodyPanelRowKind
import com.coui.appcompat.preference.COUIPreference
import com.coui.appcompat.preference.COUIPreferenceCategory
import com.coui.appcompat.preference.COUISwitchPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.3c wiring check for the group applier (plan §3 M4.3c): the new「高级功能」category is built from
 * envelope rows against the host's reflection surface, inserted after `sound`, and re-applied
 * idempotently. The stand-ins live at the real host package names
 * (`com.coui.appcompat.preference`, `com.oplus.melody.common.widget`), so the class lookup the applier
 * performs is exercised exactly as it is on a phone.
 */
class MelodyPanelGroupApplierTest {

    @Test
    fun apply_insertsTheGroupAfterSoundAndFillsTheRows() {
        val screen = FakeScreen()
        val sound = screen.addGroup("sound", order = 5)
        val spatial = screen.addRow("pref_spatial_audio", layout = LAYOUT_RES)

        val changed = apply(
            screen,
            policy(equalizerRow(), upscalingRow()),
            stateText = { state -> if (state == "upscaling") "true" else "Bright" },
        )

        assertTrue(changed)
        val category = requireNotNull(categoryOf(screen))
        assertEquals(MelodyPanelGroup.ADVANCED_KEY, category.getKey())
        assertEquals("高级功能", category.getTitle())
        // Order is `sound.getOrder() + 1`, so the group lands directly after the official sound group.
        assertEquals(6, category.getOrder())
        // The category cloned the sibling row's layout resources for the native look.
        assertEquals(LAYOUT_RES, category.getLayoutResource())
        // The screen's own children survive; the group is appended after them.
        assertEquals(3, screen.children().size)
        assertSame(sound, screen.children()[0])
        assertSame(spatial, screen.children()[1])

        val rows = (category as COUIPreferenceCategory).children().filterIsInstance<COUIPreference>()
        assertEquals(listOf("melody_bridge_eqPreset", "melody_bridge_upscaling"), rows.map { it.getKey() })
        assertEquals(listOf("Equalizer", "DSEE HX upscaling"), rows.map { it.getTitle() })
        // The equalizer row uses the native jump style: the value sits on the right (`assignment`).
        assertEquals("Bright", rows[0].getAssignment())
        // A switch row is bound to the live boolean value.
        assertEquals(true, (rows[1] as COUISwitchPreference).isChecked())
    }

    @Test
    fun apply_isIdempotentAcrossTicks() {
        val screen = FakeScreen()
        screen.addGroup("sound", order = 1)
        screen.addRow("pref_spatial_audio", layout = LAYOUT_RES)
        val plan = policy(equalizerRow(), upscalingRow())

        apply(screen, plan, stateText = { "Off" })
        val category = requireNotNull(categoryOf(screen))
        val afterFirst = (category as COUIPreferenceCategory).children().size

        val changedAgain = apply(screen, plan, stateText = { "Off" })

        assertFalse("a second pass must not re-insert", changedAgain)
        assertSame(category, categoryOf(screen))
        assertEquals(afterFirst, (categoryOf(screen) as COUIPreferenceCategory).children().size)
    }

    @Test
    fun apply_reFillsLiveValuesWithoutReAddingRows() {
        val screen = FakeScreen()
        screen.addGroup("sound", order = 1)
        screen.addRow("pref_spatial_audio", layout = LAYOUT_RES)
        val plan = policy(equalizerRow(), upscalingRow())

        apply(screen, plan, stateText = { "Off" })
        apply(screen, plan, stateText = { "Bright" })

        val rows = (requireNotNull(categoryOf(screen)) as COUIPreferenceCategory)
            .children().filterIsInstance<COUIPreference>()
        assertEquals("Bright", rows[0].getAssignment())
    }

    @Test
    fun readOnlyRowsAreNotSelectable() {
        val screen = FakeScreen()
        screen.addGroup("sound", order = 1)
        screen.addRow("pref_spatial_audio", layout = LAYOUT_RES)
        val valueRow = MelodyPanelRow(
            kind = MelodyPanelRowKind.VALUE,
            key = "melody_bridge_battery_left",
            title = "Left earbud",
            state = "battery.left",
            unit = "%",
        )

        apply(screen, policy(valueRow), stateText = { "80" })

        val row = (requireNotNull(categoryOf(screen)) as COUIPreferenceCategory)
            .children().filterIsInstance<COUIPreference>().single()
        assertEquals(false, row.isSelectable())
        assertEquals("80%", row.getSummary())
    }

    @Test
    fun apply_hidesTheStaleGroupWhenThePolicyDropsIt() {
        val screen = FakeScreen()
        screen.addGroup("sound", order = 1)
        apply(screen, policy(equalizerRow()), stateText = { null })
        val category = requireNotNull(categoryOf(screen))

        val changed = apply(screen, MelodyPanelPolicy(sectionTitle = "Advanced"), stateText = { null })

        assertTrue(changed)
        assertFalse(category.isVisible())
    }

    @Test
    fun apply_withoutTheHostClasses_failsOpen() {
        val screen = FakeScreen()

        val changed = apply(screen, policy(equalizerRow()), stateText = { null }, loader = BlankLoader())

        assertFalse(changed)
        assertEquals(0, screen.children().size)
    }

    @Test
    fun interactiveRows_getTheClickBinderButReadOnlyRowsDoNot() {
        val screen = FakeScreen()
        screen.addGroup("sound", order = 1)
        val bound = mutableListOf<String>()
        val binder = MelodyRowClickBinder { _, row, _ ->
            bound += row.key
            true
        }

        apply(screen, policy(equalizerRow(), upscalingRow(), readOnlyRow()), stateText = { "Off" }, clickBinder = binder)

        assertEquals(listOf("melody_bridge_eqPreset", "melody_bridge_upscaling"), bound)
    }

    @Test
    fun interactiveRows_areBoundOnlyOnceAcrossTicks() {
        val screen = FakeScreen()
        screen.addGroup("sound", order = 1)
        var binds = 0
        val binder = MelodyRowClickBinder { _, _, _ ->
            binds++
            true
        }
        val plan = policy(equalizerRow(), upscalingRow())

        apply(screen, plan, stateText = { "Off" }, clickBinder = binder)
        apply(screen, plan, stateText = { "Off" }, clickBinder = binder)

        assertEquals(2, binds)
    }

    @Test
    fun aChangedRowValue_notifiesTheHostSoTheBoundViewRebinds() {
        val screen = FakeScreen()
        screen.addGroup("sound", order = 1)
        val plan = policy(equalizerRow())
        apply(screen, plan, stateText = { "Off" })
        val row = (requireNotNull(categoryOf(screen)) as COUIPreferenceCategory)
            .children().filterIsInstance<COUIPreference>().single()
        val before = row.notifyCount()

        // The next tick sees the value the device actually switched to.
        apply(screen, plan, stateText = { "Bright" })

        assertEquals("Bright", row.getAssignment())
        assertTrue("a value change must nudge the host to rebind", row.notifyCount() > before)
    }

    @Test
    fun anUnchangedRowValue_doesNotRenotify() {
        val screen = FakeScreen()
        screen.addGroup("sound", order = 1)
        val plan = policy(equalizerRow())
        apply(screen, plan, stateText = { "Off" })
        val row = (requireNotNull(categoryOf(screen)) as COUIPreferenceCategory)
            .children().filterIsInstance<COUIPreference>().single()
        val before = row.notifyCount()

        apply(screen, plan, stateText = { "Off" })

        assertEquals(before, row.notifyCount())
    }

    // --- drivers ----------------------------------------------------------------------------------

    private fun apply(
        screen: FakeScreen,
        policy: MelodyPanelPolicy,
        stateText: (String) -> String?,
        loader: ClassLoader = requireNotNull(javaClass.classLoader),
        clickBinder: MelodyRowClickBinder? = null,
    ): Boolean = MelodyPanelGroupApplier.apply(
        screenId = "DetailMainActivity",
        screen = screen,
        context = null,
        policy = policy,
        stateText = MelodyRowStateText(stateText),
        mac = "14:3F:A6:02:5F:B0",
        loader = loader,
        log = MelodyGroupLog { _, _ -> },
        clickBinder = clickBinder,
    )

    private fun categoryOf(screen: FakeScreen): COUIPreference? =
        screen.children().filterIsInstance<COUIPreference>()
            .firstOrNull { it.getKey() == MelodyPanelGroup.ADVANCED_KEY }

    private fun policy(vararg rows: MelodyPanelRow): MelodyPanelPolicy = MelodyPanelPolicy(
        sectionTitle = "高级功能",
        group = MelodyPanelGroup(
            key = MelodyPanelGroup.ADVANCED_KEY,
            title = "高级功能",
            rows = rows.toList(),
        ),
    )

    private fun equalizerRow(): MelodyPanelRow = MelodyPanelRow(
        kind = MelodyPanelRowKind.SEGMENTED,
        key = "melody_bridge_eqPreset",
        title = "Equalizer",
        state = "eqPreset",
        action = "eq.set",
        options = listOf("off", "bright"),
        optionLabels = listOf("Off", "Bright"),
    )

    private fun upscalingRow(): MelodyPanelRow = MelodyPanelRow(
        kind = MelodyPanelRowKind.SWITCH,
        key = "melody_bridge_upscaling",
        title = "DSEE HX upscaling",
        state = "upscaling",
        action = "upscaling.set",
    )

    private fun readOnlyRow(): MelodyPanelRow = MelodyPanelRow(
        kind = MelodyPanelRowKind.VALUE,
        key = "melody_bridge_level",
        title = "Level",
        state = "level",
    )

    // --- stand-ins shaped like the host surface ---------------------------------------------------

    /** A plain row with the host accessor names; the applier only ever reaches rows reflectively. */
    @Suppress("unused")
    private class FakeRow(private val key: String, private var layout: Int = 0) {
        private var title: CharSequence? = null
        private var summary: CharSequence? = null
        private var selectable = true
        private var visible = true
        fun getKey(): String = key
        fun setKey(value: String?) = Unit
        fun getTitle(): CharSequence? = title
        fun setTitle(value: CharSequence?) { title = value }
        fun getSummary(): CharSequence? = summary
        fun setSummary(value: CharSequence?) { summary = value }
        fun isSelectable(): Boolean = selectable
        fun setSelectable(value: Boolean) { selectable = value }
        fun isVisible(): Boolean = visible
        fun setVisible(value: Boolean) { visible = value }
        fun isEnabled(): Boolean = true
        fun setEnabled(value: Boolean) = Unit
        fun getOrder(): Int = 0
        fun setOrder(value: Int) = Unit
        fun getLayoutResource(): Int = layout
        fun setLayoutResource(value: Int) { layout = value }
        fun getWidgetLayoutResource(): Int = 0
        fun setWidgetLayoutResource(value: Int) = Unit
        fun getContext(): Any? = null
        fun notifyChanged() = Unit
    }

    /** The screen: `PreferenceScreen` enumerates its children, but its `addPreference` is renamed. */
    @Suppress("unused")
    private class FakeScreen {
        private val items = mutableListOf<Any>()

        fun getPreferenceCount(): Int = items.size
        fun getPreference(index: Int): Any = items[index]
        fun children(): List<Any> = items

        /** Obfuscated `addPreference`. */
        fun qq(preference: Any) { items.add(preference) }

        fun addGroup(key: String, order: Int): Any {
            val group = FakeGroup(key, order)
            items.add(group)
            return group
        }

        fun addRow(key: String, layout: Int): Any {
            val row = FakeRow(key, layout)
            items.add(row)
            return row
        }
    }

    /** Matches the fake `sound` group: it *is* a group, so it is skipped when picking a layout template. */
    @Suppress("unused")
    private class FakeGroup(private val key: String, private val order: Int) {
        fun getKey(): String = key
        fun getOrder(): Int = order
        fun getPreferenceCount(): Int = 0
        fun getPreference(index: Int): Any = error("empty")
        fun isVisible(): Boolean = true
        // The official `sound` category carries the white-card layout the new group clones.
        fun getLayoutResource(): Int = LAYOUT_RES
        fun getWidgetLayoutResource(): Int = 0
    }

    /** Cannot resolve the host classes, which is the "anchor drifted" case the applier must survive. */
    private class BlankLoader : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            throw ClassNotFoundException(name)
    }

    private companion object {
        const val LAYOUT_RES = 0x7f0a0001
    }
}

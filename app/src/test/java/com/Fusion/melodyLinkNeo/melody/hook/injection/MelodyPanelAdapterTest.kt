package com.fusion.melodyLinkNeo.melody.hook.injection

import com.fusion.melodyLinkNeo.melody.api.MelodyPanelPolicy
import com.fusion.melodyLinkNeo.melody.api.PanelVisibilityApplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.2 wiring check for the reflection adapter (plan §3 M4.2, spec §7.2): the real host rows are
 * R8-minified, so the applier reaches them only through `getKey`/`isVisible`/`setVisible`/`isEnabled`/
 * `setEnabled`/`getPreferenceCount`/`getPreference`. The stand-ins below expose exactly those names, so
 * a rename of the accessors the adapter relies on shows up here instead of only on a phone.
 *
 * Lives in the JVM suite because the adapter is plain reflection; the runtime host behaviour is still
 * only provable on a device and is covered by the plan's M4.5 matrix.
 */
class MelodyPanelAdapterTest {

    @Test
    fun adapter_hidesAndGreysThroughTheHostAccessors() {
        val sound = FakeGroup("sound")
        val equalizer = FakePreference("pref_equalizer")
        sound.add(equalizer)
        val earphone = FakeGroup("earphone")
        val firmware = FakePreference("pref_firmware")
        earphone.add(firmware)
        val screen = FakeScreen(listOf(earphone, sound))

        val result = PanelVisibilityApplier.apply(
            policy(hideSections = setOf("earphone"), greyKeys = setOf("pref_equalizer")),
            MelodyPanelAdapter.rowsOf(screen),
        )

        assertEquals(listOf("earphone"), result.hiddenSections)
        assertFalse(earphone.isVisible())
        assertTrue(earphone.isEnabled())
        assertEquals(listOf("pref_equalizer"), result.greyedKeys)
        assertTrue(equalizer.isVisible())
        assertFalse(equalizer.isEnabled())
        // Hiding is `setVisible(false)`; the host still manages the same child instances.
        assertSame(equalizer, sound.rows()[0])
    }

    @Test
    fun adapter_collapsesAnEnumeratedGroupThatBecameEmpty() {
        val sound = FakeGroup("sound")
        sound.add(FakePreference("pref_a"), FakePreference("pref_b"))
        val screen = FakeScreen(listOf(sound))

        val result = PanelVisibilityApplier.apply(
            policy(hideKeys = setOf("pref_a", "pref_b")),
            MelodyPanelAdapter.rowsOf(screen),
        )

        assertEquals(listOf("sound"), result.hiddenEmptySections)
        assertFalse(sound.isVisible())
    }

    @Test
    fun adapter_isIdempotentOnASecondPass() {
        val earphone = FakeGroup("earphone")
        earphone.add(FakePreference("pref_more_setting"))
        val screen = FakeScreen(listOf(earphone))
        val plan = policy(hideSections = setOf("earphone"))

        val first = PanelVisibilityApplier.apply(plan, MelodyPanelAdapter.rowsOf(screen))
        val second = PanelVisibilityApplier.apply(plan, MelodyPanelAdapter.rowsOf(screen))

        assertEquals(1, first.changeCount)
        assertTrue(second.isEmpty)
        assertFalse(earphone.isVisible())
    }

    // --- stand-ins shaped like the host (method names, not types) --------------------------------

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

    @Suppress("unused")
    private open class FakePreference(private val key: String?) {
        private var visibleState = true
        private var enabledState = true

        fun getKey(): String? = key
        fun isVisible(): Boolean = visibleState
        fun setVisible(value: Boolean) { visibleState = value }
        fun isEnabled(): Boolean = enabledState
        fun setEnabled(value: Boolean) { enabledState = value }
    }

    @Suppress("unused")
    private class FakeGroup(key: String?) : FakePreference(key) {
        private val items = mutableListOf<FakePreference>()

        fun add(vararg preferences: FakePreference) { items += preferences }
        fun rows(): List<FakePreference> = items
        fun getPreferenceCount(): Int = items.size
        fun getPreference(index: Int): Any = items[index]
    }

    @Suppress("unused")
    private class FakeScreen(private val items: List<FakePreference>) {
        fun getPreferenceCount(): Int = items.size
        fun getPreference(index: Int): Any = items[index]
    }
}

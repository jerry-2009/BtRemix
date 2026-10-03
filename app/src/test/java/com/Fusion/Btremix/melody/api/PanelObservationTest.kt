package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 coverage for the official-panel inventory model (MELODY_BRIDGE_SPEC §7.2).
 *
 * The hooks themselves only run inside `com.oplus.melody`, so the parts that must be provably correct
 * on the JVM are the row bookkeeping (add/update/unchanged, ordering) and the Markdown projection that
 * becomes `docs/melody-official-keys.md` after a real-device run.
 */
class PanelObservationTest {

    private fun row(
        screen: String = "DetailMainActivity",
        key: String = "pref_noise_switch",
        title: String? = "噪声控制",
        className: String = "com.coui.appcompat.preference.COUISwitchPreference",
        visible: Boolean = true,
        enabled: Boolean = true,
        order: Int = 10,
        depth: Int = 0,
    ) = PanelKeyRow(screen, key, title, className, visible, enabled, order, depth)

    @Test
    fun record_reportsAddedThenUnchangedThenUpdated() {
        val catalog = PanelKeyCatalog()

        assertEquals(PanelKeyChange.ADDED, catalog.record(row()))
        assertEquals(PanelKeyChange.UNCHANGED, catalog.record(row()))
        assertEquals(PanelKeyChange.UPDATED, catalog.record(row(visible = false)))
        assertEquals(1, catalog.size)
        assertFalse(catalog.rows().single().visible)
    }

    @Test
    fun record_keepsRowsThatShareAKeyButDifferByClass() {
        val catalog = PanelKeyCatalog()

        catalog.record(row(className = "a.Preference"))
        catalog.record(row(className = "b.Preference"))

        assertEquals(2, catalog.size)
    }

    @Test
    fun rows_areOrderedByScreenThenPanelOrder() {
        val catalog = PanelKeyCatalog()
        catalog.record(row(screen = "OneSpaceDetailActivity", key = "b", order = 20))
        catalog.record(row(screen = "DetailMainActivity", key = "z", order = 30))
        catalog.record(row(screen = "DetailMainActivity", key = "a", order = 5))

        assertEquals(
            listOf(
                "DetailMainActivity/a",
                "DetailMainActivity/z",
                "OneSpaceDetailActivity/b",
            ),
            catalog.rows().map { it.screen + "/" + it.key },
        )
    }

    @Test
    fun markdown_groupsByScreenAndSanitisesCells() {
        val catalog = PanelKeyCatalog()
        catalog.record(row())
        catalog.record(
            row(
                screen = "OneSpaceDetailActivity",
                key = "pref_more_setting",
                title = "a|b\nc",
                className = "COUIPreference",
                visible = false,
                order = 2,
                depth = 1,
            ),
        )

        val markdown = renderPanelKeysMarkdown(catalog.rows(), hostVersion = "17.6.3", generatedAt = "2026-10-04T00:00:00+0800")

        assertTrue(markdown.startsWith("# Melody 官方控件 Key 清单"))
        assertTrue(markdown.contains("> 宿主版本：17.6.3"))
        assertTrue(markdown.contains("> 观测行数：2"))
        assertTrue(markdown.contains("## DetailMainActivity"))
        assertTrue(markdown.contains("## OneSpaceDetailActivity"))
        assertTrue(markdown.contains("| `pref_noise_switch` | 噪声控制 |"))
        assertTrue(markdown.contains("a\\|b c"))
        assertFalse(markdown.contains("a|b\nc"))
    }

    @Test
    fun markdown_emptyCatalog_RendersPlaceholder() {
        val markdown = renderPanelKeysMarkdown(emptyList())

        assertTrue(markdown.contains("> 观测行数：0"))
        assertTrue(markdown.contains("尚未观测到任何行"))
    }
}

package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.definition.api.MelodyPanelDefinition

/**
 * Structural view of one host Preference row, as the M4.2 applier needs it
 * (HANDOFF_MELODY_M4_PLAN.md §3 M4.2, MELODY_BRIDGE_SPEC §7.2).
 *
 * The host bundles R8-minified `androidx.preference`/COUI classes, so the panel hook cannot reference
 * a `Preference` type at compile time. Modelling the tree as this tiny interface keeps the rule
 * engine - which rows to hide, which to grey, which groups collapsed to empty - pure Kotlin the JVM
 * tests can drive with fakes, while the Android side only supplies a reflection-backed adapter.
 */
interface PanelRow {
    /** The host `Preference.key`; empty when the row carries none (never matches a policy key). */
    val key: String

    /** `true` for a `PreferenceGroup` (screen/category) - the only rows a section rule may hide. */
    val isGroup: Boolean

    val isVisible: Boolean
    val isEnabled: Boolean

    /** Child rows for a group, empty otherwise. An unenumerable group reports no children. */
    fun children(): List<PanelRow>

    fun setVisible(visible: Boolean)
    fun setEnabled(enabled: Boolean)
}

/**
 * What one [PanelVisibilityApplier.apply] call actually changed. Only rows whose live value differed
 * from the requested one are listed, so a re-application against an already-processed panel reports
 * [isEmpty] and writes nothing (the idempotency M4.2 requires across host refreshes).
 */
data class MelodyPanelApplyResult(
    val hiddenSections: List<String> = emptyList(),
    val hiddenKeys: List<String> = emptyList(),
    val greyedKeys: List<String> = emptyList(),
    /** Groups hidden only because every child ended up invisible (spec §7.2 "隐藏空分组"). */
    val hiddenEmptySections: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = hiddenSections.isEmpty() && hiddenKeys.isEmpty() &&
            greyedKeys.isEmpty() && hiddenEmptySections.isEmpty()

    /** `changed` count for the summary log. */
    val changeCount: Int
        get() = hiddenSections.size + hiddenKeys.size + greyedKeys.size + hiddenEmptySections.size

    companion object {
        val NONE = MelodyPanelApplyResult()
    }
}

/**
 * Applies a [MelodyPanelPolicy] to a panel tree (M4.2, spec D5).
 *
 * Rule order is fixed and matches the plan and the spec:
 *  1. `hideSections` -> whole group `setVisible(false)`;
 *  2. `hideKeys` -> row `setVisible(false)`;
 *  3. `greyKeys` -> row `setEnabled(false)` (stays visible);
 *  4. empty-group cleanup -> a group whose children all ended up invisible is hidden too.
 *
 * Everything is fail-open: an inert policy ([MelodyPanelPolicy.present] is `false`) returns
 * [MelodyPanelApplyResult.NONE] without touching a single row, and the `melody_bridge_*` namespace is
 * refused even when a hand-built policy (bypassing the envelope validator) names it.
 *
 * Writes are idempotent by construction: a row is only written when its live value differs from the
 * requested one, which is also what makes a fresh write land after the host rebuilds its list.
 */
object PanelVisibilityApplier {

    fun apply(policy: MelodyPanelPolicy, roots: List<PanelRow>): MelodyPanelApplyResult {
        if (!policy.present) return MelodyPanelApplyResult.NONE

        val hiddenSections = LinkedHashSet<String>()
        val hiddenKeys = LinkedHashSet<String>()
        val greyedKeys = LinkedHashSet<String>()
        val hiddenEmpty = LinkedHashSet<String>()

        walk(roots) { row ->
            when {
                row.isGroup && matches(policy.hideSections, row.key) -> {
                    if (row.hide()) hiddenSections += row.key
                }

                !row.isGroup && matches(policy.hideKeys, row.key) -> {
                    if (row.hide()) hiddenKeys += row.key
                }

                !row.isGroup && matches(policy.greyKeys, row.key) -> {
                    if (row.grey()) greyedKeys += row.key
                }
            }
        }

        roots.forEach { collapseEmptyGroups(it, hiddenEmpty) }

        return MelodyPanelApplyResult(
            hiddenSections = hiddenSections.toList(),
            hiddenKeys = hiddenKeys.toList(),
            greyedKeys = greyedKeys.toList(),
            hiddenEmptySections = hiddenEmpty.toList(),
        )
    }

    /** A rule only ever matches a real, non-empty official key; our own namespace is off limits. */
    private fun matches(keys: Set<String>, key: String): Boolean =
        key.isNotEmpty() &&
            !key.startsWith(MelodyPanelDefinition.CUSTOM_KEY_PREFIX) &&
            key in keys

    private fun walk(rows: List<PanelRow>, visit: (PanelRow) -> Unit) {
        for (row in rows) {
            visit(row)
            if (row.isGroup) walk(row.children(), visit)
        }
    }

    /**
     * Post-order collapse: children are processed before their parent, so a nested group that just
     * became empty also makes its parent eligible. A group with no enumerable children is left alone
     * (we cannot tell "no children" from "children not visible to reflection"), which keeps the
     * cleanup fail-open.
     */
    private fun collapseEmptyGroups(row: PanelRow, hidden: MutableSet<String>) {
        if (!row.isGroup) return
        val children = row.children()
        children.forEach { collapseEmptyGroups(it, hidden) }
        if (row.isVisible && children.isNotEmpty() && children.none { it.isVisible }) {
            row.setVisible(false)
            hidden += row.key
        }
    }

    private fun PanelRow.hide(): Boolean {
        if (!isVisible) return false
        setVisible(false)
        return true
    }

    private fun PanelRow.grey(): Boolean {
        if (!isEnabled) return false
        setEnabled(false)
        return true
    }
}

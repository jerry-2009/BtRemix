package com.Fusion.Btremix.melody.projection

import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.MelodyPanelDefinition
import com.Fusion.Btremix.definition.api.UiNode
import com.Fusion.Btremix.melody.api.MelodyPanelActionArgs
import com.Fusion.Btremix.melody.api.MelodyPanelArg
import com.Fusion.Btremix.melody.api.MelodyPanelGroup
import com.Fusion.Btremix.melody.api.MelodyPanelRow
import com.Fusion.Btremix.melody.api.MelodyPanelRowKind

/**
 * Routes the Definition's `ui` tree into the self-built「高级功能」group (M4.3c, decision D-8,
 * `HANDOFF_MELODY_M4_PLAN.md` §3 M4.3c).
 *
 * Ownership is decided **per node** from the state/action it is bound to, via
 * [MelodyCapabilityMap.domainOf]; no per-node configuration field is added to the schema. Nodes in a
 * host-native domain (battery -> header, ANC mode/strength -> native `noise` group) are dropped, the
 * rest become [MelodyPanelRow]s. Containers (`Column`, `Section`) are transparent: M4.3c builds a
 * single category, so the group title comes from `panel.sectionTitle` and the node titles are
 * preserved on the rows.
 *
 * The whole function is pure and Definition-driven, which is what lets the routing contract - and
 * each node -> row mapping - be pinned by JVM tests instead of only on a phone. `null` means "the
 * Definition has no non-native control", and the caller then inserts nothing.
 */
object MelodyUiRouting {

    fun advancedGroup(definition: LoadedDeviceDefinition, sectionTitle: String? = null): MelodyPanelGroup? {
        val title = sectionTitle?.takeIf { it.isNotBlank() } ?: definition.manifest.displayName
        val rows = ArrayList<MelodyPanelRow>()
        val usedKeys = HashSet<String>()
        walk(definition, definition.ui.children, rows, usedKeys)
        if (rows.isEmpty()) return null
        return MelodyPanelGroup(key = MelodyPanelGroup.ADVANCED_KEY, title = title, rows = rows)
    }

    private fun walk(
        definition: LoadedDeviceDefinition,
        nodes: List<UiNode>,
        rows: MutableList<MelodyPanelRow>,
        usedKeys: MutableSet<String>,
    ) {
        for (node in nodes) {
            when (node) {
                is UiNode.Column -> walk(definition, node.children, rows, usedKeys)
                is UiNode.Section -> walk(definition, node.children, rows, usedKeys)
                else -> rowOf(definition, node)?.let { row ->
                    rows += row.copy(key = uniqueKey(row.key, usedKeys))
                }
            }
        }
    }

    private fun rowOf(definition: LoadedDeviceDefinition, node: UiNode): MelodyPanelRow? = when (node) {
        is UiNode.Switch -> rowOf(definition, MelodyPanelRowKind.SWITCH, node.id, node.state, node.action)
        is UiNode.Segmented -> rowOf(
            definition,
            MelodyPanelRowKind.SEGMENTED,
            node.id,
            node.state,
            node.action,
            options = node.options,
        )
        is UiNode.Slider -> rowOf(definition, MelodyPanelRowKind.SLIDER, node.id, node.state, node.action)
        is UiNode.Value -> rowOf(definition, MelodyPanelRowKind.VALUE, node.id, node.state, action = null)
        is UiNode.Text -> rowOf(
            definition,
            MelodyPanelRowKind.TEXT,
            node.id,
            node.state,
            action = null,
            title = node.text,
        )
        is UiNode.Progress -> rowOf(definition, MelodyPanelRowKind.PROGRESS, node.id, node.state, action = null)
        is UiNode.Button -> {
            // M4.4: the literal args must survive as typed values, so a scalar-only wire form is used.
            // A structured arg (List/Map) has no wire form and greys the row out instead of firing half
            // of an action.
            val typed = LinkedHashMap<String, MelodyPanelArg>(node.args.size)
            var argsOk = true
            for ((name, value) in node.args) {
                val arg = MelodyPanelActionArgs.argOf(value)
                if (arg == null) {
                    argsOk = false
                    break
                }
                typed[name] = arg
            }
            rowOf(
                definition,
                MelodyPanelRowKind.BUTTON,
                node.id,
                state = null,
                action = node.action,
                title = node.label,
                args = if (argsOk) typed else emptyMap(),
                extraUnavailable = !argsOk,
            )
        }
        // Containers never reach here: `walk` expands them in place.
        is UiNode.Column, is UiNode.Section -> null
    }

    private fun rowOf(
        definition: LoadedDeviceDefinition,
        kind: MelodyPanelRowKind,
        id: String?,
        state: String?,
        action: String?,
        title: String? = null,
        options: List<String> = emptyList(),
        args: Map<String, MelodyPanelArg> = emptyMap(),
        extraUnavailable: Boolean = false,
    ): MelodyPanelRow? {
        // Native domains are provided by the host; M4.3c must not duplicate them.
        if (MelodyCapabilityMap.domainOf(definition, state, action) != MelodyUiDomain.ADVANCED) return null
        val stateDefinition = state?.let { definition.states[it] }
        val actionKnown = action == null || definition.actions.containsKey(action)
        // A node whose state/action the Definition does not declare is still shown (its position in the
        // panel is useful), but greyed so the user cannot mistake it for a working control.
        val unavailable = (state != null && stateDefinition == null) || !actionKnown || extraUnavailable
        val resolvedTitle = title?.takeIf { it.isNotBlank() }
            ?: stateDefinition?.displayName?.takeIf { it.isNotBlank() }
            ?: state
            ?: action
            ?: return null
        val optionLabels = options.map { option ->
            stateDefinition?.enumValues?.get(option)?.takeIf { it.isNotBlank() } ?: option
        }
        // M4.4: the execute argument name for the single-value kinds (`Switch`/`Segmented`/`Slider`).
        // Mirrors `DefinitionRenderer.actionParameter`: the action's first declared parameter, else
        // "value". Read-only nodes (no action) carry no param.
        val param = action
            ?.takeIf { it.isNotBlank() }
            ?.let { actionId ->
                MelodyPanelActionArgs.paramName(definition.actions[actionId]?.parameters?.firstOrNull()?.name)
            }
        return MelodyPanelRow(
            kind = kind,
            key = keyFor(id, state, action, resolvedTitle),
            title = resolvedTitle,
            state = state,
            action = action,
            param = param,
            valueType = stateDefinition?.type?.name?.lowercase(),
            args = args,
            options = options,
            optionLabels = optionLabels,
            min = stateDefinition?.min,
            max = stateDefinition?.max,
            step = stateDefinition?.step,
            unit = stateDefinition?.unit,
            unavailable = unavailable,
        )
    }

    /** `melody_bridge_<slug>`; the id/state/action keeps it stable across panel rebuilds. */
    private fun keyFor(id: String?, state: String?, action: String?, title: String): String {
        val base = id?.takeIf { it.isNotBlank() }
            ?: state?.takeIf { it.isNotBlank() }
            ?: action?.takeIf { it.isNotBlank() }
            ?: title
        val slug = base.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return MelodyPanelDefinition.CUSTOM_KEY_PREFIX + slug
    }

    /** Two nodes may resolve to the same slug; the second (and later) get a deterministic suffix. */
    private fun uniqueKey(candidate: String, used: MutableSet<String>): String {
        if (used.add(candidate)) return candidate
        var index = 2
        while (!used.add(candidate + "_" + index)) index++
        return candidate + "_" + index
    }

}

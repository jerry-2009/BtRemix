package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.definition.api.MelodyPanelDefinition

/**
 * The host control a routed Definition `ui` node becomes (M4.3c, `MELODY_BRIDGE_SPEC` §7.3,
 * `HANDOFF_MELODY_M4_PLAN.md` §3 M4.3c).
 *
 * The panel hook never sees a Definition, so the node -> control decision travels inside the
 * projection envelope as one of these kinds. Values map 1:1 to the spec's `UiNode` table:
 * `Switch` -> [SWITCH], `Segmented` -> [SEGMENTED], `Slider` -> [SLIDER], `Value` / `Text` /
 * `Progress` -> the read-only [VALUE] / [TEXT] / [PROGRESS], `Button` -> [BUTTON].
 */
enum class MelodyPanelRowKind {
    SWITCH,
    SEGMENTED,
    SLIDER,
    VALUE,
    TEXT,
    PROGRESS,
    BUTTON;

    /** `true` for the kinds M4.4 wires to `IMelodyBridge.execute`; the rest stay read-only. */
    val interactive: Boolean
        get() = this == SWITCH || this == SEGMENTED || this == SLIDER || this == BUTTON

    /** The wire token the envelope carries (`function panel.group.rows[].kind`). */
    val wire: String get() = name.lowercase()

    companion object {
        /** Parses a wire token; `null` for an unknown kind, which makes the row fail-open. */
        fun fromWire(token: String?): MelodyPanelRowKind? =
            entries.firstOrNull { it.wire == token?.trim()?.lowercase() }
    }
}

/** Wire tokens for [MelodyPanelRow.valueType]; mirrors `StateDefinitionType.name.lowercase()`. */
object MelodyPanelValueType {
    const val BOOLEAN = "boolean"
    const val INTEGER = "integer"
    const val NUMBER = "number"
    const val STRING = "string"
    const val ENUM = "enum"
    const val BYTES = "bytes"
}

/** Wire tokens for [MelodyPanelArg.type]; mirrors the `StateValue` subtype. */
object MelodyPanelArgType {
    const val BOOLEAN = "boolean"
    const val INT = "int"
    const val LONG = "long"
    const val FLOAT = "float"
    const val DOUBLE = "double"
    const val STRING = "string"
    const val BYTES = "bytes"
}

/**
 * One typed literal argument of a `Button` row (M4.4). The envelope is JSON, so the value travels as
 * a one-line string; [type] is what lets the host rebuild the exact `StateValue` the Compose renderer
 * would have sent (`DefinitionRenderer` hands `UiNode.Button.args` straight to `DeviceAction`).
 *
 * Only scalar kinds are representable. A `List`/`Map` argument has no wire form here, and the row is
 * greyed out rather than firing a half-built action (see `MelodyUiRouting`).
 */
data class MelodyPanelArg(
    val type: String,
    val value: String,
)

/**
 * One row of BtRemix's self-built「高级功能」group (M4.3c, decision D-8).
 *
 * It is the envelope-side projection of one non-native `UiNode`, resolved by
 * `com.Fusion.Btremix.melody.projection.MelodyUiRouting` on the BtRemix side (which owns the
 * Definition) and rebuilt into a host `Preference` by the panel hook. [key] is always inside the
 * `melody_bridge_*` namespace so hide/grey rules can never touch it (spec §7.2).
 *
 * [state] is the Definition state the row reads its live value from; [action]/[param]/[args] are
 * carried for the click link (M4.4). [param] is the execute argument name for the single-value kinds
 * (`Switch`/`Segmented`/`Slider`); [valueType] is the declared state type used to coerce a `Slider`
 * value to the same `StateValue` the Compose renderer would send. [unavailable] marks a row whose
 * state/action the Definition does not declare, or whose literal args cannot be rebuilt - it is still
 * shown, but greyed out.
 */
data class MelodyPanelRow(
    val kind: MelodyPanelRowKind,
    val key: String,
    val title: String,
    val state: String? = null,
    val action: String? = null,
    /** Execute argument name (the action's first parameter), or `null` for read-only rows. */
    val param: String? = null,
    /** Declared state type wire token, or `null` when the row has no state. */
    val valueType: String? = null,
    /** Typed literal args, carried by `Button` rows only. */
    val args: Map<String, MelodyPanelArg> = emptyMap(),
    /** `Segmented` option values, in display order. */
    val options: List<String> = emptyList(),
    /** Display labels aligned to [options]; a missing/short entry falls back to the raw value. */
    val optionLabels: List<String> = emptyList(),
    /** `Slider` bounds, from the state's own `min`/`max`/`step`. */
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    /** Display suffix for `Value` / `Progress` summaries (e.g. `%`). */
    val unit: String? = null,
    val unavailable: Boolean = false,
)

/**
 * The card BtRemix inserts after the official `sound` group (M4.3c, D-8). It is one host category
 * keyed [key] carrying the routed [rows]; an empty group is never emitted, so a Definition whose
 * `ui` is entirely native material produces no group at all and the host panel is left untouched.
 */
data class MelodyPanelGroup(
    val key: String,
    val title: String,
    val rows: List<MelodyPanelRow>,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        /** The fixed key of the inserted group; node rows are prefixed with the same namespace. */
        const val ADVANCED_KEY: String = "melody_bridge_advanced"

        /** True when [key] is inside the BtRemix-only namespace the hide/grey rules must never match. */
        fun isCustomKey(key: String): Boolean = key.startsWith(MelodyPanelDefinition.CUSTOM_KEY_PREFIX)
    }
}

package com.Fusion.Btremix.melody.projection

import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.MelodyAncDefinition
import com.Fusion.Btremix.definition.api.MelodyAncStrengthDefinition
import com.Fusion.Btremix.definition.api.MelodyAncStrengthLevel
import com.Fusion.Btremix.definition.api.MelodyAncMode
import com.Fusion.Btremix.definition.api.StateDefinition
import com.Fusion.Btremix.definition.api.StateDefinitionType
import com.Fusion.Btremix.definition.json.JsonValue

/**
 * One entry of the Definition-capability -> official `WhitelistConfigDTO$Function` switch table.
 *
 * [valueOf] answers both "does this Definition actually implement the capability?" (`null` = no, the
 * switch stays neutral) and "what value should the host read?". Most switches are a constant; the
 * ANC ones are derived per Definition (M4.3b). Keeping the predicate here (instead of a hard-coded
 * name list in the builder) is what lets M4 add rows by extending the table only.
 */
class MelodyCapabilityRule(
    val functionKey: String,
    val valueOf: (LoadedDeviceDefinition) -> JsonValue?,
)

/**
 * Definition capability -> `WhitelistConfigDTO$Function` switch (MELODY_BRIDGE_SPEC §5.4 item 3,
 * HANDOFF_MELODY_M3_PLAN.md §4 M3.2, decision M3-D7).
 *
 * D4 minimizes the official UI by starting from the neutral template and turning on only switches the
 * Definition can really serve, so the host never renders an official row whose action BtRemix cannot
 * execute. The table below is deliberately restricted to switches whose value a Definition can
 * express: the remaining cloud-side switches (`equalizerMode` / `control` / `callControl` / ...) are
 * cloud-side mode tables with protocol indices a Definition cannot express, so they have no rule and
 * stay at the template's neutral value forever (M4 delivers their rows as `melody_bridge_*` custom
 * entries instead). `noiseReductionMode` used to be in that group; M4.3b (D-7/D-11/D-12) added a rule
 * that derives the host's native noise-reduction table from `melody.anc`.
 *
 * [ENABLED_IN_M3] is still **empty** (the M3-D7 contract, kept so the neutral envelope stays testable);
 * M4 ships [ENABLED_IN_M4], which the projection builder now uses by default: the `spatialTypes` list
 * of [SPATIAL_TYPES] (M4.1) plus the `noiseReductionMode` / `noiseReductionUIVersion` pair (M4.3b).
 * The ANC strength is not a separate switch: it rides along as the `childrenMode` list of the
 * `noiseReductionMode` entry (M4.3b D-15).
 */
object MelodyCapabilityMap {

    /** How the host encodes "this switch is on" for the scalar capability bits. */
    private val SWITCH_ON: JsonValue = JsonValue.NumberValue("1")

    /** Scalar capability switches a Definition can back today. */
    val rules: List<MelodyCapabilityRule> = listOf(
        MelodyCapabilityRule("batteryInfo") { definition -> SWITCH_ON.takeIf { providesBattery(definition) } },
        // M4.1: the *only* switch that makes the host render the official 音质音效 group on a device
        // that has no SDK session. The group's one usable row is「空间音频」, which is a phone-side
        // Spatializer feature (M4-D2), so the honest Definition-side declaration is "I want the
        // official sound group", not "my headset implements spatial audio". Everything else in the
        // group stays off, so no row BtRemix cannot serve is rendered.
        MelodyCapabilityRule(SPATIAL_TYPES) { definition ->
            spatialTypesValue().takeIf { providesSpatialPanel(definition) }
        },
        // M4.3b (D-7/D-11/D-12): the host's native `noise` group is gated solely by a non-empty
        // `noiseReductionMode` table, whose protocol indices are the ANC mode projection contract.
        MelodyCapabilityRule(NOISE_REDUCTION_MODE) { definition ->
            ancPlan(definition).takeUnless { it.isEmpty }?.let(::noiseReductionModeValue)
        },
        MelodyCapabilityRule(NOISE_REDUCTION_UI_VERSION) { definition ->
            ancPlan(definition).takeUnless { it.isEmpty }
                ?.let { JsonValue.NumberValue(it.uiVersion.toString()) }
        },
    )

    /** Capability switches this milestone is allowed to turn on. Empty is the M3-D7 contract. */
    val ENABLED_IN_M3: Set<String> = emptySet()

    /**
     * M4's frozen switch set: M4.1's one field for「音质音效 + 空间音频」plus M4.3b's native ANC pair
     * (HANDOFF_MELODY_M4_PLAN.md §3 M4.1 / §3 M4.3b).
     */
    val ENABLED_IN_M4: Set<String> = setOf(SPATIAL_TYPES, NOISE_REDUCTION_MODE, NOISE_REDUCTION_UI_VERSION)

    /** `WhitelistConfigDTO$Function.spatialTypes`; a *list*, non-null is what the host checks. */
    const val SPATIAL_TYPES: String = "spatialTypes"

    /** `WhitelistConfigDTO$Function.noiseReductionMode`; the native ANC mode table (M4.3b). */
    const val NOISE_REDUCTION_MODE: String = "noiseReductionMode"

    /** `WhitelistConfigDTO$Function.noiseReductionUIVersion`; selects the host's render order. */
    const val NOISE_REDUCTION_UI_VERSION: String = "noiseReductionUIVersion"

    /**
     * Definition state key the ANC mode is read from. The frozen `melody.anc` node (D-12) carries the
     * mode *values*, not the state key, so both the derivation and the host-side index projection use
     * this shared convention (`docs/melody-capability-map.md` §7.1).
     */
    const val ANC_STATE_KEY: String = "ancMode"

    /** Definition state key the ANC strength is read from (conventional, M4.3b D-14). */
    const val LEVEL_STATE_KEY: String = "ancLevel"

    /** Definition `manifest.capabilities` token that opts into the official sound group (M4.1). */
    const val CAPABILITY_SPATIAL: String = "spatial"

    /** Host child `modeType`s the derived「降噪效果」table uses (Low / Moderate / High). */
    private const val LOW_MODE_TYPE: Int = 3
    private const val MODERATE_MODE_TYPE: Int = 8
    private const val HIGH_MODE_TYPE: Int = 4

    /** First `protocolIndex` the derived child table reserves; declared tables pick their own. */
    private const val DERIVED_CHILD_INDEX_BASE: Int = 100

    /**
     * Host `modeType` vocabulary (17.6.3): the only values `NoiseReductionItem.updateActionView`
     * renders. The host has no "wind" slot, so `wind` maps to the Adaptive (10) slot and relies on
     * the D-12 label hook.
     */
    private val MODE_TYPES: Map<String, Int> = linkedMapOf(
        "off" to 1,
        "anc" to 5,
        "ambient" to 2,
        "wind" to 10,
    )

    /** `modeType` the host falls back to when the projected protocol index matches nothing. */
    const val OFF_MODE_TYPE: Int = 1

    /**
     * The `function` overrides for [definition], limited to [enabledKeys].
     *
     * The default ([ENABLED_IN_M4]) is the M4.1 set; passing a key set explicitly is how the JVM tests
     * drive other points of the table (including the empty M3-D7 baseline).
     */
    fun overrides(
        definition: LoadedDeviceDefinition,
        enabledKeys: Set<String> = ENABLED_IN_M4,
    ): Map<String, JsonValue> = buildMap {
        rules.forEach { rule ->
            if (rule.functionKey !in enabledKeys) return@forEach
            rule.valueOf(definition)?.let { put(rule.functionKey, it) }
        }
    }

    /**
     * The ANC mode table for [definition]: the `melody.anc` declaration when it carries a table,
     * otherwise a table derived from the definition's ANC enum state by name convention
     * (`off`/`anc`/`ambient`/`wind` -> `1`/`5`/`2`/`10`). Labels missing from the declaration fall
     * back to the state's own `enumValues` display name (M4.3b D-12). The strength half (D-14) is the
     * declared `melody.anc.strength`, else the `[min, midpoint, max]` derivation of the bounded
     * integer ANC state.
     */
    fun ancPlan(definition: LoadedDeviceDefinition): MelodyAncPlan {
        val declared = definition.melody?.anc
        val uiVersion = declared?.uiVersion ?: MelodyAncDefinition.DEFAULT_UI_VERSION
        val declaredModes = declared?.modes.orEmpty()
        val modes = if (declaredModes.isNotEmpty()) declaredModes else deriveAncModes(definition)
        val state = ancStateOf(definition)
        return MelodyAncPlan(
            uiVersion = uiVersion,
            modes = modes.map { mode ->
                mode.copy(label = mode.label ?: state?.enumValues?.get(mode.state))
            },
            strength = declared?.strength ?: deriveAncStrength(definition),
        )
    }

    /**
     * The Definition's ANC strength as a native「降噪效果」list (M4.3b D-15), derived only when the
     * package did not declare `melody.anc.strength` explicitly. Conventional key `ancLevel` first,
     * then any bounded integer state in the ANC domain whose name is not the mode state; the action is
     * the one whose `resultState` is that state. The three derived positions are the host's
     * Low / Moderate / High, standing for `[min, midpoint, max]` - for Sony's 1..20 that is
     * `[1, 10, 20]`. A package that wants other anchors declares them in the Definition.
     */
    private fun deriveAncStrength(definition: LoadedDeviceDefinition): MelodyAncStrengthDefinition? {
        val state = definition.states[LEVEL_STATE_KEY]?.takeIf { it.type == StateDefinitionType.INTEGER }
            ?: definition.states.values.firstOrNull { candidate ->
                candidate.type == StateDefinitionType.INTEGER &&
                    candidate.key != ANC_STATE_KEY &&
                    candidate.key.contains("anc", ignoreCase = true) &&
                    candidate.min != null && candidate.max != null
            }
            ?: return null
        val low = (state.min ?: return null).toInt()
        val high = (state.max ?: return null).toInt()
        if (high <= low) return null
        val action = definition.actions.values.firstOrNull { it.resultState == state.key } ?: return null
        return MelodyAncStrengthDefinition(
            state = state.key,
            action = action.id,
            levels = listOf(
                MelodyAncStrengthLevel(LOW_MODE_TYPE, DERIVED_CHILD_INDEX_BASE, low),
                MelodyAncStrengthLevel(MODERATE_MODE_TYPE, DERIVED_CHILD_INDEX_BASE + 1, (low + high) / 2),
                MelodyAncStrengthLevel(HIGH_MODE_TYPE, DERIVED_CHILD_INDEX_BASE + 2, high),
            ),
        )
    }

    /** The `noiseReductionMode` array in the shape the host's `NoiseReductionMode` parser expects. */
    private fun noiseReductionModeValue(plan: MelodyAncPlan): JsonValue = JsonValue.Array(
        plan.modes.map { mode ->
            val fields = linkedMapOf<String, JsonValue>(
                "modeType" to JsonValue.NumberValue(mode.modeType.toString()),
                "protocolIndex" to JsonValue.NumberValue(mode.protocolIndex.toString()),
                // `decideByEarDevice=false` makes `isNoiseReductionModeSupported` answer `true`
                // without a `NoiseReductionInfoDTO` (docs/melody-capability-map.md §7.1.2).
                "decideByEarDevice" to JsonValue.BooleanValue(false),
            )
            // M4.3b D-15: the host renders its native「降噪效果」row (NoiseReductionChildModeManager)
            // only for the Noise-cancelling entry when that entry carries a non-empty `childrenMode`.
            if (mode.modeType == MelodyAncStrengthDefinition.HOST_PARENT_MODE_TYPE) {
                plan.strength?.levels?.takeIf { it.isNotEmpty() }?.let { levels ->
                    fields["childrenMode"] = JsonValue.Array(levels.map(::ancStrengthLevel))
                }
            }
            JsonValue.Object(fields)
        },
    )

    /**
     * One `childrenMode` entry in the host's shape. The real whitelist rows carry only
     * `modeType`/`protocolIndex`, and the child `modeType` chooses the host's own title
     * (`Ba.r.b`: 3 Low / 8 Moderate / 4 High / 7 Auto).
     */
    private fun ancStrengthLevel(level: MelodyAncStrengthLevel): JsonValue = JsonValue.Object(
        linkedMapOf(
            "modeType" to JsonValue.NumberValue(level.modeType.toString()),
            "protocolIndex" to JsonValue.NumberValue(level.protocolIndex.toString()),
        ),
    )

    /** The definition's ANC enum state: the conventional `ancMode` key first, then any ANC-shaped enum. */
    private fun ancStateOf(definition: LoadedDeviceDefinition): StateDefinition? {
        definition.states[ANC_STATE_KEY]?.takeIf { it.type == StateDefinitionType.ENUM }?.let { return it }
        return definition.states.values.firstOrNull { state ->
            state.type == StateDefinitionType.ENUM &&
                state.enumValues.keys.any { it.lowercase() in MODE_TYPES }
        }
    }

    private fun deriveAncModes(definition: LoadedDeviceDefinition): List<MelodyAncMode> {
        val state = ancStateOf(definition) ?: return emptyList()
        return state.enumValues.entries.mapIndexedNotNull { index, (value, label) ->
            val modeType = MODE_TYPES[value.lowercase()] ?: return@mapIndexedNotNull null
            // The protocol index is opaque to the host (it only compares it with the projected
            // current index), so the enum order is a stable, collision-free choice.
            MelodyAncMode(modeType = modeType, protocolIndex = index, state = value, label = label)
        }
    }

    /**
     * True when the Definition declares a battery state, either through `manifest.capabilities`
     * (`"battery"`) or through a `battery*` state key such as the Sony package's `battery.left` /
     * `battery.case`. The state-key fallback keeps hand-written packages honest even when they forget
     * the capability list.
     */
    private fun providesBattery(definition: LoadedDeviceDefinition): Boolean =
        definition.manifest.capabilities.any { it.equals("battery", ignoreCase = true) } ||
            definition.states.keys.any { it.startsWith("battery", ignoreCase = true) }

    /**
     * True when the Definition asks for the official sound group. The value is the shape observed on
     * real whitelist entries (44 of 94 models carry `[0,1]`; `[0]` / `[1]` render identically, see
     * `docs/melody-capability-map.md` §3 M4.1), and the host only tests it for `null`.
     */
    private fun providesSpatialPanel(definition: LoadedDeviceDefinition): Boolean =
        definition.manifest.capabilities.any { it.equals(CAPABILITY_SPATIAL, ignoreCase = true) }

    private fun spatialTypesValue(): JsonValue = JsonValue.Array(
        listOf(JsonValue.NumberValue("0"), JsonValue.NumberValue("1")),
    )
}

/** The host ANC render plan derived for one Definition (M4.3b D-12); see [MelodyCapabilityMap.ancPlan]. */
data class MelodyAncPlan(
    /** `function.noiseReductionUIVersion`: the host's render order selector (1 or 2). */
    val uiVersion: Int,
    /** `function.noiseReductionMode`: the mode table, in the definition's declaration order. */
    val modes: List<MelodyAncMode>,
    /** The「降噪效果」strength mapping, or `null` when the Definition has none (M4.3b D-15). */
    val strength: MelodyAncStrengthDefinition? = null,
) {
    val isEmpty: Boolean get() = modes.isEmpty()

    companion object {
        val EMPTY: MelodyAncPlan = MelodyAncPlan(MelodyAncDefinition.DEFAULT_UI_VERSION, emptyList())
    }
}

/**
 * Maps a Definition's integer `support.productType` to the official `WhitelistConfigDTO.type` string
 * (MELODY_BRIDGE_SPEC §4.2, HANDOFF_MELODY_M3_PLAN.md §4 M3.0/M3.2).
 *
 * The M3.-1 export (94 real entries) shows `content.type` is a **form-factor** token, not a numeric
 * code: `N` covers the neckband models (OPPO Enco M3x, OnePlus Bullets Wireless), `O1` the clip/open
 * models (Enco Clip / Buds Clip / Open Buds), and `T1` / `T2` the TWS families. `DeviceInfo.mProductType`
 * is a separate integer field, so the export cannot tell us the numeric -> token mapping; M3 defines the
 * table below and treats every unmapped value as "leave the template neutral" instead of inventing a
 * token. The 1:1 values are provisional and are re-checked against a whitelisted device in M3.5.
 */
object MelodyProductType {

    /** Matches [com.Fusion.Btremix.definition.api.MelodySupportDefinition.DEFAULT_PRODUCT_TYPE]. */
    const val DEFAULT: Int = 1

    const val TWS_FIRST_GEN: Int = 1
    const val TWS_SECOND_GEN: Int = 2
    const val NECKBAND: Int = 3
    const val CLIP_OPEN: Int = 4

    /** Every `content.type` value observed in the M3.-1 export. */
    val OBSERVED_TYPES: Set<String> = setOf("T1", "T2", "N", "O1")

    /** Official form-factor token for [productType], or `null` when M3 has no mapping for it. */
    fun officialType(productType: Int): String? = when (productType) {
        TWS_FIRST_GEN -> "T1"
        TWS_SECOND_GEN -> "T2"
        NECKBAND -> "N"
        CLIP_OPEN -> "O1"
        else -> null
    }
}

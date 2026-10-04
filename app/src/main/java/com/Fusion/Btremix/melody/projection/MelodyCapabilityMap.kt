package com.Fusion.Btremix.melody.projection

import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.JsonValue

/**
 * One entry of the Definition-capability -> official `WhitelistConfigDTO$Function` switch table.
 *
 * [providedBy] answers "does this Definition actually implement the capability?"; [enabledValue] is
 * the value the host reads as "on". Keeping the predicate here (instead of a hard-coded name list in
 * the builder) is what lets M4 add rows by extending the table only.
 */
class MelodyCapabilityRule(
    val functionKey: String,
    val enabledValue: JsonValue,
    val providedBy: (LoadedDeviceDefinition) -> Boolean,
)

/**
 * Definition capability -> `WhitelistConfigDTO$Function` switch (MELODY_BRIDGE_SPEC §5.4 item 3,
 * HANDOFF_MELODY_M3_PLAN.md §4 M3.2, decision M3-D7).
 *
 * D4 minimizes the official UI by starting from the neutral template and turning on only switches the
 * Definition can really serve, so the host never renders an official row whose action BtRemix cannot
 * execute. The table below is deliberately restricted to switches whose value is a *plain scalar*:
 * structured switches (`noiseReductionMode` / `equalizerMode` / `control` / `callControl` / ...) are
 * cloud-side mode tables with protocol indices that a Definition cannot express, so they have no rule
 * and stay at the template's neutral value forever (M4 delivers their rows as `melody_bridge_*` custom
 * entries instead).
 *
 * M3 itself ships with [ENABLED_IN_M3] **empty**: every switch stays exactly as the template left it
 * (M3-D7). The table plus the [overrides] seam is the part M4 flips, one switch at a time, without
 * touching the projection builder.
 */
object MelodyCapabilityMap {

    /** How the host encodes "this switch is on" for the scalar capability bits. */
    private val SWITCH_ON: JsonValue = JsonValue.NumberValue("1")

    /** Scalar capability switches a Definition can back today. */
    val rules: List<MelodyCapabilityRule> = listOf(
        MelodyCapabilityRule("batteryInfo", SWITCH_ON, ::providesBattery),
    )

    /** Capability switches this milestone is allowed to turn on. Empty is the M3-D7 contract. */
    val ENABLED_IN_M3: Set<String> = emptySet()

    /**
     * The `function` overrides for [definition], limited to [enabledKeys].
     *
     * The default ([ENABLED_IN_M3]) is empty, so the envelope stays neutral in M3; passing a key set
     * explicitly is how the M4 table (and the JVM tests) drive the same code path.
     */
    fun overrides(
        definition: LoadedDeviceDefinition,
        enabledKeys: Set<String> = ENABLED_IN_M3,
    ): Map<String, JsonValue> = buildMap {
        rules.forEach { rule ->
            if (rule.functionKey in enabledKeys && rule.providedBy(definition)) {
                put(rule.functionKey, rule.enabledValue)
            }
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

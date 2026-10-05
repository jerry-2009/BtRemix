package com.fusion.melodyLinkNeo.melody.api

import com.fusion.melodyLinkNeo.device.runtime.StateValue
import kotlin.math.roundToLong

/**
 * Turns a「高级功能」row's live/selected value into the exact `DeviceAction` argument map the Compose
 * renderer would have sent (M4.4, `HANDOFF_MELODY_M4_PLAN.md` §3 M4.4, spec §7.4).
 *
 * The host panel only has the envelope, not the Definition, so the row carries everything needed to
 * rebuild the argument: [MelodyPanelRow.param] (the action's first parameter name), [MelodyPanelRow.valueType]
 * (the declared state type, so a `Slider` becomes the same `IntValue`/`DoubleValue` the renderer picked)
 * and, for `Button`, the typed literal args. This object is pure Kotlin - it is the whole click-link
 * contract that JVM tests pin, while the Android reflection lives in the panel hook.
 *
 * Every function returns `null` for "this row cannot be executed"; the caller then logs and skips
 * instead of firing a partial action (fail-open).
 */
object MelodyPanelActionArgs {

    /** Argument name used when the row (and the action) declares none; mirrors `DefinitionRenderer`. */
    const val DEFAULT_PARAM: String = "value"

    /** `true` for the strings a host `Switch` can carry as "on"; anything else is off. */
    fun isTruthy(value: String?): Boolean = when (value?.trim()?.lowercase()) {
        "true", "1", "on", "yes" -> true
        else -> false
    }

    /** `Switch`: the flipped boolean. */
    fun switch(row: MelodyPanelRow, newChecked: Boolean): Map<String, StateValue>? =
        singleArgument(row, StateValue.BooleanValue(newChecked))

    /** `Segmented`: the chosen option value. */
    fun choice(row: MelodyPanelRow, option: String): Map<String, StateValue>? =
        singleArgument(row, StateValue.StringValue(option))

    /** `Slider`: the chosen numeric value, quantized to `step` and clamped to `min..max`. */
    fun slider(row: MelodyPanelRow, value: Double): Map<String, StateValue>? {
        if (row.kind != MelodyPanelRowKind.SLIDER) return null
        if (!value.isFinite()) return null
        val min = row.min ?: 0.0
        val max = row.max ?: 100.0
        if (!min.isFinite() || !max.isFinite() || max < min) return null
        val quantized = quantize(value, min, max, row.step)
        val argument = if (row.valueType == MelodyPanelValueType.INTEGER) {
            StateValue.IntValue(quantized.roundToLong().toInt())
        } else {
            StateValue.DoubleValue(quantized)
        }
        return singleArgument(row, argument)
    }

    /** `Button`: the typed literal args (not wrapped in [DEFAULT_PARAM]). */
    fun button(row: MelodyPanelRow): Map<String, StateValue>? {
        if (row.kind != MelodyPanelRowKind.BUTTON) return null
        if (row.action.isNullOrBlank()) return null
        val args = LinkedHashMap<String, StateValue>(row.args.size)
        for ((name, arg) in row.args) {
            args[name] = stateValueOf(arg) ?: return null
        }
        return args
    }

    /**
     * The envelope form of one literal `UiNode.Button` argument, or `null` for a structured value that
     * has no wire form here (`List`/`Map`) - the routing then greys the whole row out.
     */
    fun argOf(value: StateValue): MelodyPanelArg? = when (value) {
        is StateValue.BooleanValue -> MelodyPanelArg(MelodyPanelArgType.BOOLEAN, value.value.toString())
        is StateValue.IntValue -> MelodyPanelArg(MelodyPanelArgType.INT, value.value.toString())
        is StateValue.LongValue -> MelodyPanelArg(MelodyPanelArgType.LONG, value.value.toString())
        is StateValue.FloatValue -> MelodyPanelArg(MelodyPanelArgType.FLOAT, value.value.toString())
        is StateValue.DoubleValue -> MelodyPanelArg(MelodyPanelArgType.DOUBLE, value.value.toString())
        is StateValue.StringValue -> MelodyPanelArg(MelodyPanelArgType.STRING, value.value)
        is StateValue.BytesValue ->
            MelodyPanelArg(MelodyPanelArgType.BYTES, value.value.joinToString("") { "%02x".format(it) })
        is StateValue.ListValue, is StateValue.MapValue -> null
    }

    /** The single declared parameter name of the first action, or [DEFAULT_PARAM]. */
    fun paramName(firstParameter: String?): String = firstParameter?.takeIf { it.isNotBlank() } ?: DEFAULT_PARAM

    private fun singleArgument(row: MelodyPanelRow, value: StateValue): Map<String, StateValue>? {
        if (!row.kind.interactive) return null
        if (row.action.isNullOrBlank()) return null
        val param = row.param?.takeIf { it.isNotBlank() } ?: DEFAULT_PARAM
        return mapOf(param to value)
    }

    /** Quantizes to the step grid anchored at `min`, then clamps; a missing/zero step leaves it exact. */
    private fun quantize(value: Double, min: Double, max: Double, step: Double?): Double {
        val clamped = value.coerceIn(min, max)
        val size = step?.takeIf { it.isFinite() && it > 0.0 } ?: return clamped
        val steps = ((clamped - min) / size).roundToLong()
        return (min + steps * size).coerceIn(min, max)
    }

    private fun stateValueOf(arg: MelodyPanelArg): StateValue? = when (arg.type.trim().lowercase()) {
        MelodyPanelArgType.BOOLEAN -> arg.value.toBooleanStrictOrNull()?.let { StateValue.BooleanValue(it) }
        MelodyPanelArgType.INT -> arg.value.toIntOrNull()?.let { StateValue.IntValue(it) }
        MelodyPanelArgType.LONG -> arg.value.toLongOrNull()?.let { StateValue.LongValue(it) }
        MelodyPanelArgType.FLOAT -> arg.value.toFloatOrNull()?.let { StateValue.FloatValue(it) }
        MelodyPanelArgType.DOUBLE -> arg.value.toDoubleOrNull()?.let { StateValue.DoubleValue(it) }
        MelodyPanelArgType.STRING -> StateValue.StringValue(arg.value)
        MelodyPanelArgType.BYTES -> decodeHex(arg.value)?.let { StateValue.BytesValue(it) }
        else -> null
    }

    /** Lowercase/uppercase hex pairs; `null` on any non-hex character or an odd length. */
    private fun decodeHex(text: String): ByteArray? {
        val clean = text.trim()
        if (clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (index in out.indices) {
            val hi = Character.digit(clean[index * 2], 16)
            val lo = Character.digit(clean[index * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[index] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

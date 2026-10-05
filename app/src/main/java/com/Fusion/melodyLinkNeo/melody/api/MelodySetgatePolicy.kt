package com.fusion.melodyLinkNeo.melody.api

import com.fusion.melodyLinkNeo.definition.json.JsonParser
import com.fusion.melodyLinkNeo.definition.json.JsonValue

/**
 * The `com.oplus.melody.setgate` broadcast payload (M5.2, `HANDOFF_MELODY_M5_PLAN.md` §4 M5.2 /
 * `docs/melody-capability-map.md` §8.5).
 *
 * The device card, settings and OneSpace publish the target as a JSON string in the `extra` intent extra
 * (`NoiseReductionCommand.onReceive`), carrying the MAC and a `type` that is the host's **`modeType`**
 * (either a JSON number or a numeric string). `melody_from` is a sibling String extra that says who sent
 * it (`one_space` / `card`); it is only used for the log line, so this parser ignores it.
 *
 * Parsing is pure and total: a missing key, a non-numeric `type`, a malformed JSON blob or a missing
 * `extra` all return `null`, which the caller turns into a fail-open `chain.proceed()`.
 */
object MelodySetgatePolicy {

    /** MAC key inside the `extra` JSON (`docs/melody-capability-map.md` §8.5). */
    const val KEY_MAC: String = "mac"

    /** Target key inside the `extra` JSON; its value is the host `modeType`, not a `protocolIndex`. */
    const val KEY_TYPE: String = "type"

    /** One decoded `setgate` command. */
    data class Command(val mac: String, val modeType: Int)

    /** Decodes the `extra` JSON, or `null` when it does not carry a usable MAC + `modeType`. */
    fun parse(extraJson: String?): Command? {
        val root = extraJson
            ?.let { runCatching { JsonParser.parse(it) as? JsonValue.Object }.getOrNull() }
            ?: return null
        val mac = (root.values[KEY_MAC] as? JsonValue.StringValue)?.value
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val modeType = intOf(root.values[KEY_TYPE]) ?: return null
        return Command(mac = mac, modeType = modeType)
    }

    private fun intOf(value: JsonValue?): Int? = when (value) {
        is JsonValue.NumberValue -> value.raw.trim().toIntOrNull()
        is JsonValue.StringValue -> value.value.trim().toIntOrNull()
        else -> null
    }
}

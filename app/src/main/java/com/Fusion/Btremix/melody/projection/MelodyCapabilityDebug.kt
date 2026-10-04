package com.Fusion.Btremix.melody.projection

import com.Fusion.Btremix.definition.json.JsonParser
import com.Fusion.Btremix.definition.json.JsonValue
import java.io.File

/**
 * M4.1 experiment rig: a **debug-only** override of individual `WhitelistConfigDTO$Function` fields
 * (HANDOFF_MELODY_M4_PLAN.md §3 M4.1, "定位能力位").
 *
 * The production path only ever flips switches that [MelodyCapabilityMap] has a rule for and the
 * Definition really backs. Locating *which* switch makes Melody render「音质音效 / 空间音频」 needs the
 * opposite: set one raw field at a time and look at the resulting panel, without rebuilding the
 * capability table each round. This object is that seam.
 *
 * It is deliberately inert outside debug builds: [read] returns an empty map unless `debugBuild` is
 * true, so a release APK always projects exactly what M3-D7/M4's rule table says. The file lives in
 * our own `filesDir` (written with `adb shell run-as com.Fusion.Btremix …` during the experiment),
 * never in anything the host reads.
 *
 * File format — one `function=value` per line, `#` starts a comment:
 *
 * ```text
 * # M4.1 round 3: does spatialTypes alone light the sound group?
 * spatialTypes=[0,1]
 * equalizer=1
 * ```
 *
 * `value` is parsed as JSON, so scalars (`1`, `-1`, `true`, `"T1"`), arrays (`[0,1]`) and objects all
 * work; anything that is not valid JSON is taken as a raw string. A missing `=` means `=1`, and an
 * empty value also means `1` — the common "turn this bit on" case.
 */
object MelodyCapabilityDebug {

    /** Name of the override file inside the app's private `filesDir`. */
    const val FILE_NAME: String = "melody-capability-debug.txt"

    /** Reads the override from [file]; empty unless this is a debug build and the file exists. */
    fun read(debugBuild: Boolean, file: File?): Map<String, JsonValue> {
        if (!debugBuild || file == null) return emptyMap()
        val text = runCatching { file.takeIf(File::isFile)?.readText() }.getOrNull()
        return parse(text)
    }

    /** Parses the override text; never throws — a malformed line is skipped rather than fatal. */
    fun parse(text: String?): Map<String, JsonValue> {
        if (text.isNullOrBlank()) return emptyMap()
        val overrides = LinkedHashMap<String, JsonValue>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf('=')
            val key = (if (separator < 0) line else line.substring(0, separator)).trim()
            if (key.isEmpty()) return@forEach
            val raw = if (separator < 0) "1" else line.substring(separator + 1).trim()
            overrides[key] = valueOf(raw.ifEmpty { "1" })
        }
        return overrides
    }

    private fun valueOf(raw: String): JsonValue =
        runCatching { JsonParser.parse(raw) }.getOrDefault(JsonValue.StringValue(raw))
}

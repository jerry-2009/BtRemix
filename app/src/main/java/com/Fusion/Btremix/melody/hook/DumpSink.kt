package com.Fusion.Btremix.melody.hook

import android.content.Context
import com.Fusion.Btremix.melody.api.PanelKeyCatalog
import com.Fusion.Btremix.melody.api.PanelKeyRow
import com.Fusion.Btremix.melody.api.renderPanelKeysMarkdown
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the observed panel inventory inside the host app data directory (MELODY_BRIDGE_SPEC §7.2).
 *
 * Logcat alone is fragile for a few hundred rows, so alongside the structured `melody.panel.row.*`
 * events the module writes the finished `docs/melody-official-keys.md` plus a machine-readable JSON
 * twin to `<host filesDir>/btremix_melody_dump/`. On a rooted test device both are pulled with a
 * single `adb pull /data/data/com.oplus.melody/files/btremix_melody_dump/`.
 *
 * Writing is best-effort: the host process owns the directory, so every failure is logged and
 * swallowed, and an empty catalog never overwrites a previously collected dump.
 */
internal class DumpSink(private val log: MelodyLog) {

    fun write(context: Context?, catalog: PanelKeyCatalog, hostVersion: String?) {
        if (context == null || catalog.isEmpty) return
        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (!directory.isDirectory && !directory.mkdirs()) {
            log.warn("melody.panel.dump_mkdir_failed")
            return
        }
        val rows = catalog.rows()
        val timestamp = timestamp()
        runCatching {
            File(directory, MARKDOWN_NAME).writeText(renderPanelKeysMarkdown(rows, hostVersion, timestamp))
            File(directory, JSON_NAME).writeText(renderJson(rows, hostVersion, timestamp))
        }.onSuccess {
            log.event(
                "melody.panel.dump.written",
                "dir" to directory.absolutePath,
                "rows" to rows.size,
                "screens" to catalog.screens().size,
            )
        }.onFailure {
            log.warn("melody.panel.dump_failed", it)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

    private fun renderJson(rows: List<PanelKeyRow>, hostVersion: String?, timestamp: String): String = buildString {
        appendLine("{")
        appendLine("  \"generatedAt\": ${quote(timestamp)},")
        appendLine("  \"hostVersion\": ${quote(hostVersion ?: "?")},")
        appendLine("  \"rows\": [")
        rows.forEachIndexed { index, row ->
            append("    {")
            append("\"screen\": ").append(quote(row.screen)).append(", ")
            append("\"key\": ").append(quote(row.key)).append(", ")
            append("\"title\": ").append(quote(row.title)).append(", ")
            append("\"class\": ").append(quote(row.className)).append(", ")
            append("\"visible\": ").append(row.visible).append(", ")
            append("\"enabled\": ").append(row.enabled).append(", ")
            append("\"order\": ").append(row.order).append(", ")
            append("\"depth\": ").append(row.depth)
            append("}")
            if (index != rows.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun quote(value: String?): String {
        if (value == null) return "null"
        val escaped = buildString(value.length + 2) {
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }
        return "\"" + escaped + "\""
    }

    companion object {
        const val DIRECTORY_NAME: String = "btremix_melody_dump"
        const val MARKDOWN_NAME: String = "melody-official-keys.md"
        const val JSON_NAME: String = "melody-official-keys.json"
    }
}

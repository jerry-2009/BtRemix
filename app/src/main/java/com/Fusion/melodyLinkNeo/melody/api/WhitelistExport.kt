package com.fusion.melodyLinkNeo.melody.api

/**
 * Pure helpers for the M3.-1 whitelist export hook (`HANDOFF_MELODY_M3_PLAN.md` §4 M3.-1).
 *
 * M1 sampled only the first few rows of every `MelodyAliveProvider` query. M3 needs the host's own
 * `all_whitelist` / `find_whitelist` / `whitelist_content` bodies verbatim, because the neutral
 * whitelist template (`assets/melody/whitelist-template.json`) has to keep the real field set —
 * inventing the ~135 `Function` switches is exactly the risk §5.4 calls out.
 *
 * The host-side hook therefore writes every returned row to
 * `<host filesDir>/btremix_melody_dump/whitelist-*.json` plus a meta record. Naming and JSON
 * rendering live here (no Android types) so they can be unit tested on the JVM; the I/O and the
 * `Cursor` walk live in `melody/hook/WhitelistExportSink.kt`.
 */
object WhitelistExport {

    /** Shared with `melody/hook/DumpSink.kt`; both dumps live in the same host data directory. */
    const val DIRECTORY_NAME: String = "btremix_melody_dump"

    /**
     * One record per exported file, rewritten whenever an export actually changes. Records accumulate
     * for the lifetime of the host process, so run every query in one session when a single meta
     * covering all dumps is wanted (a host restart keeps the dump files but resets the meta).
     */
    const val META_FILE_NAME: String = "whitelist-export-meta.json"

    /** A single dump file never exceeds this; larger results are reported and skipped. */
    const val MAX_FILE_BYTES: Int = 2 * 1024 * 1024

    private val MAC_PATTERN = Regex("macAddress\\s*=\\s*['\"]?([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})")
    private val PRODUCT_PATTERN = Regex("productId\\s*=\\s*['\"]?([0-9A-Za-z._-]+)")

    /**
     * Maps a provider URI path to the dump file it belongs to, or `null` when the path is not one of
     * the exported whitelist surfaces (the other nine URI paths keep the M1 row sample only).
     */
    fun targetOf(path: String?, selection: String?): WhitelistExportTarget? {
        val normalized = path?.trim()?.trimStart('/')?.takeIf(String::isNotEmpty) ?: return null
        return when (normalized) {
            "all_whitelist" -> WhitelistExportTarget(normalized, "whitelist-all.json")
            "ears_whitelist" -> WhitelistExportTarget(normalized, "whitelist-ears.json")
            "whitelist_content" -> WhitelistExportTarget(normalized, "whitelist-content.json")
            "diagnosis_list" -> WhitelistExportTarget(normalized, "whitelist-diagnosis.json")
            "find_whitelist" -> WhitelistExportTarget(normalized, "whitelist-find-${findToken(selection)}.json")
            else -> null
        }
    }

    /** Extracts a MAC address from a `where` clause such as `macAddress='14:3F:...'`. */
    fun macFromSelection(selection: String?): String? =
        selection?.let { MAC_PATTERN.find(it)?.groupValues?.get(1) }?.uppercase()

    /**
     * File-name suffix for `find_whitelist`. `find_whitelist` is queried either by `macAddress` or by
     * `productId` + `deviceName`; the MAC form is what the plan asks for and the productId form is the
     * fallback. Colons are replaced because the dump directory is pulled to Windows workstations.
     */
    fun findToken(selection: String?): String {
        macFromSelection(selection)?.let { return it.replace(':', '-') }
        val product = selection?.let { PRODUCT_PATTERN.find(it)?.groupValues?.get(1) }
        return sanitizeToken(product ?: "unknown")
    }

    fun sanitizeToken(raw: String): String {
        val cleaned = buildString(raw.length) {
            for (ch in raw) append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-')
        }
        return cleaned.trim('-').take(48).ifEmpty { "unknown" }
    }

    /**
     * Renders our own dump document. Values are stored exactly as the host returned them (the dump is
     * a verbatim record, not a re-encoding of the host DTO), so `derive-whitelist-template.ps1` can
     * hand `content` straight back to a JSON parser.
     */
    fun renderDocument(
        target: WhitelistExportTarget,
        caller: String?,
        selection: String?,
        columns: List<String>,
        rows: List<List<WhitelistExportCell>>,
        hostVersion: String?,
        timestamp: String,
    ): String = buildString {
        appendLine("{")
        append("  \"generatedAt\": ").append(quote(timestamp)).appendLine(",")
        append("  \"hostVersion\": ").append(quote(hostVersion)).appendLine(",")
        append("  \"path\": ").append(quote(target.path)).appendLine(",")
        append("  \"caller\": ").append(quote(caller)).appendLine(",")
        append("  \"selection\": ").append(quote(selection)).appendLine(",")
        append("  \"columns\": ").appendLine("[")
        columns.forEachIndexed { index, column ->
            append("    ").append(quote(column))
            appendLine(if (index == columns.lastIndex) "" else ",")
        }
        appendLine("  ],")
        append("  \"rowCount\": ").append(rows.size).appendLine(",")
        appendLine("  \"rows\": [")
        rows.forEachIndexed { rowIndex, row ->
            append("    {")
            columns.forEachIndexed { columnIndex, column ->
                if (columnIndex > 0) append(", ")
                append(quote(column)).append(": ").append(renderCell(row.getOrNull(columnIndex)))
            }
            append("}")
            appendLine(if (rowIndex == rows.lastIndex) "" else ",")
        }
        appendLine("  ]")
        append("}")
    }

    /** `whitelist-export-meta.json`: caller / selection / columns / rowCount / hashes per file. */
    fun renderMeta(entries: List<WhitelistExportSummary>, generatedAt: String): String = buildString {
        appendLine("{")
        append("  \"generatedAt\": ").append(quote(generatedAt)).appendLine(",")
        appendLine("  \"exports\": [")
        val ordered = entries.sortedBy { it.fileName }
        ordered.forEachIndexed { index, entry ->
            appendLine("    {")
            append("      \"file\": ").append(quote(entry.fileName)).appendLine(",")
            append("      \"path\": ").append(quote(entry.path)).appendLine(",")
            append("      \"caller\": ").append(quote(entry.caller)).appendLine(",")
            append("      \"selection\": ").append(quote(entry.selection)).appendLine(",")
            append("      \"columns\": ").append(quoteList(entry.columns)).appendLine(",")
            append("      \"rowCount\": ").append(entry.rowCount).appendLine(",")
            append("      \"hostVersion\": ").append(quote(entry.hostVersion)).appendLine(",")
            append("      \"timestamp\": ").append(quote(entry.timestamp)).appendLine(",")
            append("      \"sha256\": ").append(quote(entry.sha256)).appendLine(",")
            append("      \"byteLength\": ").append(entry.byteLength).appendLine()
            append("    }")
            appendLine(if (index == ordered.lastIndex) "" else ",")
        }
        appendLine("  ]")
        append("}")
    }

    private fun renderCell(cell: WhitelistExportCell?): String = when (cell) {
        null, WhitelistExportCell.Null -> "null"
        is WhitelistExportCell.Integer -> cell.value.toString()
        is WhitelistExportCell.Float -> if (cell.value.isFinite()) cell.value.toString() else "null"
        is WhitelistExportCell.Text -> quote(cell.value)
        is WhitelistExportCell.Blob -> buildString {
            append("{\"_blob_bytes\": ").append(cell.size)
            if (cell.base64 != null) append(", \"base64\": ").append(quote(cell.base64))
            append("}")
        }
    }

    private fun quoteList(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ", ") { quote(it) }

    fun quote(value: String?): String {
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
}

/** One exported provider surface: the URI path it came from and the dump file it lands in. */
data class WhitelistExportTarget(val path: String, val fileName: String)

/** A dumped cell; blobs are carried as base64 (or as a size-only marker when too large). */
sealed interface WhitelistExportCell {
    data object Null : WhitelistExportCell
    data class Integer(val value: Long) : WhitelistExportCell
    data class Float(val value: Double) : WhitelistExportCell
    data class Text(val value: String) : WhitelistExportCell
    data class Blob(val size: Int, val base64: String?) : WhitelistExportCell
}

/** Meta record for one dump file, surfaced in `whitelist-export-meta.json`. */
data class WhitelistExportSummary(
    val fileName: String,
    val path: String,
    val caller: String?,
    val selection: String?,
    val columns: List<String>,
    val rowCount: Int,
    val hostVersion: String?,
    val timestamp: String,
    val sha256: String,
    val byteLength: Int,
)

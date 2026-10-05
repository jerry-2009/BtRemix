package com.fusion.melodyLinkNeo.melody.hook

import android.content.Context
import android.database.Cursor
import android.util.Base64
import com.fusion.melodyLinkNeo.melody.api.WhitelistExport
import com.fusion.melodyLinkNeo.melody.api.WhitelistExportCell
import com.fusion.melodyLinkNeo.melody.api.WhitelistExportSummary
import com.fusion.melodyLinkNeo.melody.api.WhitelistExportTarget
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M3.-1 whitelist export (`HANDOFF_MELODY_M3_PLAN.md` §4 M3.-1).
 *
 * Writes the host's own whitelist provider bodies verbatim into
 * `<host filesDir>/btremix_melody_dump/` so a rooted workstation can pull them with a single
 * `adb pull /data/data/com.oplus.melody/files/btremix_melody_dump/` and feed
 * `tools/derive-whitelist-template.ps1`.
 *
 * Same discipline as [DumpSink]:
 *  * every failure is logged and swallowed — the host process must never see an exception from us;
 *  * an empty result never overwrites an existing dump (the non-whitelisted `find_whitelist` case);
 *  * a single file is capped at [WhitelistExport.MAX_FILE_BYTES];
 *  * logs carry length + sha256 only, never the payload.
 *
 * The cursor is read synchronously (the caller may close it as soon as we return; reading later would
 * race) and the cursor position is restored before returning. A re-query whose payload is unchanged
 * is skipped entirely, so a steady-state host pays a hash, not a ~500 KB rewrite.
 */
internal class WhitelistExportSink(private val log: MelodyLog) {

    private val summaries = LinkedHashMap<String, WhitelistExportSummary>()
    private val fingerprints = HashMap<String, String>()
    private var hostVersion: String? = null
    private var hostVersionRead = false

    fun export(
        context: Context?,
        target: WhitelistExportTarget,
        caller: String?,
        selection: String?,
        cursor: Cursor?,
    ) {
        // Self-guarded: the caller's runCatching would swallow a throw silently, and a diagnostic that
        // fails without saying so is worse than no diagnostic.
        runCatching { exportLocked(context, target, caller, selection, cursor) }
            .onFailure { log.warn("melody.whitelist.export_failed", it) }
    }

    private fun exportLocked(
        context: Context?,
        target: WhitelistExportTarget,
        caller: String?,
        selection: String?,
        cursor: Cursor?,
    ) {
        if (context == null || cursor == null) return
        val columns = runCatching { cursor.columnNames }.getOrNull()?.toList()
        if (columns.isNullOrEmpty()) return

        val rows = readRows(cursor, columns.size)
        if (rows == null) {
            log.event(
                "melody.whitelist.export_failed",
                "path" to target.path,
                "reason" to "too_large",
                "limit" to WhitelistExport.MAX_FILE_BYTES,
            )
            return
        }
        if (rows.isEmpty()) {
            log.event("melody.whitelist.export.skipped", "path" to target.path, "reason" to "empty")
            return
        }

        val timestamp = timestamp()
        val version = hostVersion(context)
        val document = WhitelistExport.renderDocument(
            target = target,
            caller = caller,
            selection = selection,
            columns = columns,
            rows = rows,
            hostVersion = version,
            timestamp = timestamp,
        )
        val bytes = document.toByteArray(Charsets.UTF_8)
        if (bytes.size > WhitelistExport.MAX_FILE_BYTES) {
            log.event(
                "melody.whitelist.export_failed",
                "path" to target.path,
                "reason" to "too_large",
                "bytes" to bytes.size,
            )
            return
        }

        // The document carries a timestamp, so comparing file hashes would never match. Fingerprint the
        // payload instead (same rendering with an empty timestamp): a repeated identical query against
        // an unchanged host whitelist stops here and never rewrites the file.
        val fingerprint = sha256(
            WhitelistExport.renderDocument(
                target, caller, selection, columns, rows, version, "",
            ).toByteArray(Charsets.UTF_8),
        )
        if (fingerprints[target.fileName] == fingerprint) return
        val digest = sha256(bytes)

        val directory = File(context.filesDir, WhitelistExport.DIRECTORY_NAME)
        if (!directory.isDirectory && !directory.mkdirs()) {
            log.warn("melody.whitelist.export_mkdir_failed")
            return
        }
        runCatching {
            File(directory, target.fileName).writeBytes(bytes)
            summaries[target.fileName] = WhitelistExportSummary(
                fileName = target.fileName,
                path = target.path,
                caller = caller,
                selection = selection,
                columns = columns,
                rowCount = rows.size,
                hostVersion = version,
                timestamp = timestamp,
                sha256 = digest,
                byteLength = bytes.size,
            )
            File(directory, WhitelistExport.META_FILE_NAME)
                .writeText(WhitelistExport.renderMeta(summaries.values.toList(), timestamp))
        }.onSuccess {
            fingerprints[target.fileName] = fingerprint
            log.event(
                "melody.whitelist.export.written",
                "path" to target.path,
                "file" to target.fileName,
                "rows" to rows.size,
                "bytes" to bytes.size,
                "sha256" to digest,
            )
        }.onFailure {
            log.warn("melody.whitelist.export_failed", it)
        }
    }

    /**
     * Reads every cell of every row, restoring the position the caller relies on. Returns `null` when
     * the cursor is larger than the per-file cap, so a runaway result is reported instead of dumped.
     */
    private fun readRows(cursor: Cursor, columnCount: Int): List<List<WhitelistExportCell>>? {
        val position = runCatching { cursor.position }.getOrDefault(-1)
        val rows = ArrayList<List<WhitelistExportCell>>()
        var approximateBytes = 0
        try {
            val count = runCatching { cursor.count }.getOrDefault(0)
            for (index in 0 until count) {
                if (!runCatching { cursor.moveToPosition(index) }.getOrDefault(false)) break
                val row = ArrayList<WhitelistExportCell>(columnCount)
                for (column in 0 until columnCount) {
                    val cell = cell(cursor, column)
                    approximateBytes += approximateSize(cell)
                    if (approximateBytes > WhitelistExport.MAX_FILE_BYTES) return null
                    row += cell
                }
                rows += row
            }
        } finally {
            runCatching { cursor.moveToPosition(position) }
        }
        return rows
    }

    private fun cell(cursor: Cursor, column: Int): WhitelistExportCell = runCatching {
        when (cursor.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> WhitelistExportCell.Null
            Cursor.FIELD_TYPE_INTEGER -> WhitelistExportCell.Integer(cursor.getLong(column))
            Cursor.FIELD_TYPE_FLOAT -> WhitelistExportCell.Float(cursor.getDouble(column))
            Cursor.FIELD_TYPE_STRING -> WhitelistExportCell.Text(cursor.getString(column).orEmpty())
            Cursor.FIELD_TYPE_BLOB -> blob(cursor.getBlob(column))
            else -> WhitelistExportCell.Null
        }
    }.getOrDefault(WhitelistExportCell.Null)

    /**
     * `whitelist_content` returns a serialised DO as a BLOB. Keep it readable when it is UTF-8 text
     * (the M3.3 merge wants the real bytes) and fall back to base64, bounded by the file cap.
     */
    private fun blob(bytes: ByteArray?): WhitelistExportCell {
        if (bytes == null) return WhitelistExportCell.Null
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
        if (text != null && bytes.contentEquals(text.toByteArray(Charsets.UTF_8)) && isText(text)) {
            return WhitelistExportCell.Text(text)
        }
        val base64 = if (bytes.size <= WhitelistExport.MAX_FILE_BYTES / 2) {
            runCatching { Base64.encodeToString(bytes, Base64.NO_WRAP) }.getOrNull()
        } else {
            null
        }
        return WhitelistExportCell.Blob(bytes.size, base64)
    }

    private fun isText(text: String): Boolean = text.all { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

    private fun approximateSize(cell: WhitelistExportCell): Int = when (cell) {
        WhitelistExportCell.Null -> 4
        is WhitelistExportCell.Integer -> 12
        is WhitelistExportCell.Float -> 12
        is WhitelistExportCell.Text -> cell.value.length + 6
        is WhitelistExportCell.Blob -> cell.size + (cell.base64?.length ?: 0) + 24
    }

    private fun hostVersion(context: Context): String? {
        if (!hostVersionRead) {
            hostVersionRead = true
            hostVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
        }
        return hostVersion
    }

    private fun sha256(bytes: ByteArray): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }.getOrDefault("?")

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
}

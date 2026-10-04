package com.Fusion.Btremix.melody.hook.injection

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import com.Fusion.Btremix.melody.api.MelodyInjectionTable

/**
 * The only Android-touching half of the provider injection (M3.3 plan §4 "Android 注入层").
 *
 * [materialize] reads a host cursor into plain values without disturbing the position the caller of
 * `query()` relies on, [toCursor] rebuilds our answer as a `MatrixCursor` with the host's own column
 * order so nothing downstream notices the difference.
 *
 * The official cursor is deliberately **not** closed here: the host owns its lifecycle (the original
 * return value is replaced, the framework caller closes what it receives) and closing a cursor that a
 * host implementation may still be holding is a worse failure than letting it be collected.
 */
internal object MelodyProviderCursor {

    /** Reads every row of [cursor]; `null` when there is no cursor or its shape is unusable. */
    fun materialize(cursor: Cursor?): MelodyInjectionTable? {
        if (cursor == null) return null
        val columns = runCatching { cursor.columnNames?.toList() }.getOrNull()
        if (columns.isNullOrEmpty()) return null
        val position = runCatching { cursor.position }.getOrDefault(-1)
        val rows = ArrayList<List<Any?>>()
        try {
            val count = runCatching { cursor.count }.getOrDefault(0)
            if (count > MAX_ROWS) return null
            for (index in 0 until count) {
                if (!runCatching { cursor.moveToPosition(index) }.getOrDefault(false)) break
                rows += columns.indices.map { read(cursor, it) }
            }
        } finally {
            runCatching { cursor.moveToPosition(position) }
        }
        return MelodyInjectionTable(columns, rows)
    }

    /** The cursor extras (`ears_whitelist` carries `version`) that the rebuilt cursor must keep. */
    fun extras(cursor: Cursor?): Bundle? =
        runCatching { cursor?.extras }.getOrNull()

    /** Rebuilds [table] as a `MatrixCursor`, preserving column order and copying [extras]. */
    fun toCursor(table: MelodyInjectionTable, extras: Bundle?): Cursor =
        MatrixCursor(table.columns.toTypedArray(), table.rows.size).apply {
            table.rows.forEach { row -> addRow(row.toTypedArray()) }
            extras?.let { bundle -> runCatching { setExtras(Bundle(bundle)) } }
        }

    private fun read(cursor: Cursor, column: Int): Any? = runCatching {
        when (cursor.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column)
            else -> null
        }
    }.getOrDefault(null)

    /** A 94-row `all_whitelist` answer is ~0.5 MB; anything wildly larger is not ours to rebuild. */
    private const val MAX_ROWS = 5_000
}

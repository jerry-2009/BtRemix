package com.Fusion.Btremix.melody.hook.observation

import android.content.ContentProvider
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import com.Fusion.Btremix.melody.api.WhitelistExport
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.WhitelistExportSink
import io.github.libxposed.api.XposedInterface

/**
 * M1 read-only observation of the support whitelist channel (MELODY_BRIDGE_SPEC §5.1, §12 M1).
 *
 * `MelodyAliveProvider` is how SystemUI / wireless settings / the device centre ask "is this a
 * supported headset": `query(find_whitelist)` returns a row per hit and `call(pick_equipment_image)`
 * serves card artwork. M1 only records the conversation — URI paths, selection, who asked, and a
 * bounded sample of the returned rows — because that sample is the template source for the M3
 * whitelist synthesis (`support.templateWhitelist`).
 *
 * `query`/`call` are overrides of `android.content.ContentProvider`, so R8 keeps their names; the
 * signatures are still probed (4-arg `query` with `Bundle` on API 26+) instead of assumed.
 *
 * Since M3.-1 the same hook also exports the whitelist bodies verbatim (see [WhitelistExportSink]),
 * because the M3 template can only be derived from the host's real `Function` field set.
 */
internal class SupportObservationHook(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    private val exportSink = WhitelistExportSink(log)

    fun install() {
        val providerClass = Reflect.loadClass(PROVIDER_CLASS, loader)
        if (providerClass == null) {
            log.event("melody.anchor.missing", "hook" to "support", "class" to PROVIDER_CLASS)
            return
        }
        hookQuery(providerClass)
        hookCall(providerClass)
    }

    private fun hookQuery(providerClass: Class<*>) {
        val stringArray = emptyArray<String>().javaClass
        val candidates = listOf(
            arrayOf(Uri::class.java, stringArray, String::class.java, stringArray, String::class.java),
            arrayOf(Uri::class.java, stringArray, Bundle::class.java, CancellationSignal::class.java),
        )
        val method = candidates.firstNotNullOfOrNull { Reflect.findMethod(providerClass, "query", it) }
        if (method == null) {
            log.event("melody.anchor.missing", "hook" to "support.query", "class" to PROVIDER_CLASS)
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            // The whole diagnostic path is best-effort: a failing log/probe must never surface as an
            // exception thrown into the caller of the host provider.
            runCatching {
                val provider = chain.thisObject as? ContentProvider
                val uri = chain.args.getOrNull(0) as? Uri
                log.event(
                    "melody.provider.query",
                    "caller" to runCatching { provider?.callingPackage }.getOrNull(),
                    "path" to (uri?.path ?: uri?.toString()),
                    "selection" to chain.args.getOrNull(2),
                    "args" to chain.args.getOrNull(3),
                    "method" to method.name,
                    "signature" to method.parameterTypes.size,
                )
            }
            val result = chain.proceed()
            runCatching {
                val uri = chain.args.getOrNull(0) as? Uri
                val selection = chain.args.getOrNull(2) as? String
                val target = WhitelistExport.targetOf(uri?.path ?: uri?.lastPathSegment, selection)
                if (result is Cursor) {
                    log.event(
                        "melody.provider.result",
                        "path" to (uri?.path ?: uri?.toString()),
                        "caller" to runCatching { (chain.thisObject as? ContentProvider)?.callingPackage }.getOrNull(),
                        "extras" to describeBundle(runCatching { result.extras }.getOrNull()),
                    )
                    log.detail("melody.provider.cursor", describeCursor(result))
                    if (target != null) {
                        val provider = chain.thisObject as? ContentProvider
                        exportSink.export(
                            context = provider?.context,
                            target = target,
                            caller = runCatching { provider?.callingPackage }.getOrNull(),
                            selection = selection,
                            cursor = result,
                        )
                    }
                } else if (target != null) {
                    // `find_whitelist` answers "not a supported model" with null, not an empty cursor.
                    log.event("melody.whitelist.export.skipped", "path" to target.path, "reason" to "no_cursor")
                }
            }
            result
        })
        log.event("melody.anchor.hooked", "hook" to "support.query", "class" to providerClass.name, "method" to method.name)
    }

    private fun hookCall(providerClass: Class<*>) {
        val method = Reflect.findMethod(
            providerClass,
            "call",
            arrayOf(String::class.java, String::class.java, Bundle::class.java),
        )
        if (method == null) {
            log.event("melody.anchor.missing", "hook" to "support.call", "class" to PROVIDER_CLASS)
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val methodName = chain.args.getOrNull(0)
            runCatching {
                log.event(
                    "melody.provider.call",
                    "caller" to runCatching { (chain.thisObject as? ContentProvider)?.callingPackage }.getOrNull(),
                    "method" to methodName,
                    "arg" to chain.args.getOrNull(1),
                    "extras" to describeBundle(chain.args.getOrNull(2) as? Bundle),
                )
            }
            val result = chain.proceed()
            runCatching {
                if (result is Bundle) {
                    log.event(
                        "melody.provider.call_result",
                        "method" to methodName,
                        "caller" to runCatching { (chain.thisObject as? ContentProvider)?.callingPackage }.getOrNull(),
                        "keys" to result.keySet().toList(),
                    )
                }
            }
            result
        })
        log.event("melody.anchor.hooked", "hook" to "support.call", "class" to providerClass.name)
    }

    @Suppress("DEPRECATION") // generic value dump: the typed Bundle getters do not help here
    private fun describeBundle(bundle: Bundle?): String? {
        if (bundle == null) return null
        return runCatching {
            bundle.keySet().take(MAX_EXTRAS).joinToString(",") { key ->
                key + "=" + MelodyLog.sanitize(bundle.get(key)?.toString() ?: "null", MAX_CELL_CHARS)
            }
        }.getOrNull()
    }

    /** Bounded cursor sample: reading must not disturb the position the caller relies on. */
    private fun describeCursor(cursor: Cursor): String {
        val builder = StringBuilder()
        val columns = runCatching { cursor.columnNames }.getOrNull().orEmpty()
        builder.append("count=").append(runCatching { cursor.count }.getOrDefault(-1))
        builder.append(" columns=[").append(columns.joinToString(",")).append(']')

        val position = runCatching { cursor.position }.getOrDefault(-1)
        try {
            val rows = minOf(runCatching { cursor.count }.getOrDefault(0), MAX_SAMPLE_ROWS)
            for (index in 0 until rows) {
                if (!runCatching { cursor.moveToPosition(index) }.getOrDefault(false)) break
                builder.append(" row").append(index).append('{')
                for (column in columns.indices) {
                    builder.append(columns[column]).append('=')
                    builder.append(readCell(cursor, column))
                    builder.append(';')
                }
                builder.append('}')
            }
        } finally {
            runCatching { cursor.moveToPosition(position) }
        }
        return builder.toString()
    }

    private fun readCell(cursor: Cursor, column: Int): String = runCatching {
        when (cursor.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> "null"
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column).toString()
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toString()
            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
            Cursor.FIELD_TYPE_BLOB -> "<blob:${cursor.getBlob(column)?.size ?: 0}>"
            else -> "<type${cursor.getType(column)}>"
        }
    }.getOrDefault("<error>").let { MelodyLog.sanitize(it, MAX_CELL_CHARS) }

    private companion object {
        const val PROVIDER_CLASS = "com.oplus.melody.alive.provider.MelodyAliveProvider"
        const val MAX_SAMPLE_ROWS = 3
        const val MAX_EXTRAS = 8
        const val MAX_CELL_CHARS = 240
    }
}

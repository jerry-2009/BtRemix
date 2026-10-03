package com.Fusion.Btremix.melody.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Structured logging for every Melody bridge component that runs inside `com.oplus.melody`.
 *
 * Everything is emitted in the `evt=<name> k=v` shape introduced by the M0 entry point, so a single
 * `adb logcat -s BtRemixMelody` capture is enough to reconstruct the M1 observation report. Values
 * that contain whitespace are quoted, which keeps the line machine-parseable without a second format
 * for human reading.
 *
 * All calls are defensive: the module also writes to the libxposed framework log, which is optional,
 * and a failing diagnostic must never affect the host process.
 */
internal class MelodyLog(private val module: XposedInterface?) {

    fun event(name: String, vararg pairs: Pair<String, Any?>) {
        emit(Log.INFO, "evt=" + name + pairsLine(pairs))
    }

    /** One-line annotation for a payload that is too long for key/value pairs. */
    fun detail(name: String, message: String) {
        emit(Log.INFO, "evt=" + name + " " + sanitize(message, MAX_DETAIL_CHARS))
    }

    fun warn(name: String, throwable: Throwable? = null) {
        emit(Log.WARN, "evt=" + name + if (throwable == null) "" else " error=" + describe(throwable))
    }

    private fun emit(level: Int, message: String) {
        runCatching { Log.println(level, TAG, message) }
        runCatching { module?.log(level, TAG, message) }
    }

    private fun pairsLine(pairs: Array<out Pair<String, Any?>>): String {
        if (pairs.isEmpty()) return ""
        return buildString {
            for ((key, value) in pairs) {
                append(' ').append(key).append('=').append(formatValue(value))
            }
        }
    }

    companion object {
        const val TAG: String = "BtRemixMelody"

        private const val MAX_VALUE_CHARS = 240
        private const val MAX_DETAIL_CHARS = 900

        /** `k=v` when the value is token-shaped, `k="v"` when it contains whitespace or quotes. */
        internal fun formatValue(value: Any?): String {
            if (value == null) return "null"
            val text = sanitize(value.toString(), MAX_VALUE_CHARS)
            val needsQuotes = text.isEmpty() || text.any { it.isWhitespace() } || text.contains('=')
            return if (needsQuotes) "\"" + text.replace('"', '\'') + "\"" else text
        }

        /** Collapses line breaks/tabs into spaces and bounds the length. */
        internal fun sanitize(text: String, maxChars: Int): String {
            val flattened = buildString(text.length) {
                var lastWasSpace = false
                for (ch in text) {
                    val replacement = if (ch == '\n' || ch == '\r' || ch == '\t') ' ' else ch
                    if (replacement == ' ') {
                        if (!lastWasSpace) append(' ')
                        lastWasSpace = true
                    } else {
                        append(replacement)
                        lastWasSpace = false
                    }
                }
            }.trim()
            return if (flattened.length <= maxChars) flattened else flattened.take(maxChars - 1) + "…"
        }

        internal fun describe(throwable: Throwable): String {
            val message = throwable.message
            return if (message.isNullOrBlank()) {
                throwable.javaClass.simpleName
            } else {
                throwable.javaClass.simpleName + ": " + sanitize(message, MAX_VALUE_CHARS)
            }
        }
    }
}

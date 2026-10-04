package com.Fusion.Btremix.melody.hook

import android.util.Log
import com.Fusion.Btremix.melody.api.MelodyEventFormat
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
        emit(Log.INFO, MelodyEventFormat.line(name, pairs.toList()))
    }

    /** One-line annotation for a payload that is too long for key/value pairs. */
    fun detail(name: String, message: String) {
        emit(Log.INFO, MelodyEventFormat.detailLine(name, message))
    }

    fun warn(name: String, throwable: Throwable? = null) {
        emit(Log.WARN, MelodyEventFormat.warnLine(name, throwable))
    }

    private fun emit(level: Int, message: String) {
        runCatching { Log.println(level, TAG, message) }
        runCatching { module?.log(level, TAG, message) }
    }

    companion object {
        const val TAG: String = "BtRemixMelody"

        /** `k=v` when the value is token-shaped, `k="v"` when it contains whitespace or quotes. */
        internal fun formatValue(value: Any?): String = MelodyEventFormat.formatValue(value)

        /** Collapses line breaks/tabs into spaces and bounds the length. */
        internal fun sanitize(text: String, maxChars: Int): String = MelodyEventFormat.sanitize(text, maxChars)

        internal fun describe(throwable: Throwable): String = MelodyEventFormat.describe(throwable)
    }
}

package com.fusion.melodyLinkNeo.melody.bridge

import android.util.Log
import com.fusion.melodyLinkNeo.melody.api.MelodyEventFormat

/**
 * Structured logging for the BtRemix side of the Melody bridge (MELODY_BRIDGE_SPEC §12 M2b).
 *
 * It deliberately shares the `BtRemixMelody` tag and the `evt=` shape with the injected client, so a
 * single `adb logcat -s BtRemixMelody` capture shows both halves of one exchange (the `side=` field says
 * which process emitted the line). Failure to write a log must never break a bridge call, hence the
 * `runCatching` around logcat.
 */
internal class MelodyBridgeLog(private val sink: ((String) -> Unit)? = null) {

    fun event(name: String, vararg pairs: Pair<String, Any?>) {
        val line = MelodyEventFormat.line(name, listOf("side" to SIDE, *pairs))
        emit(line)
        sink?.invoke(line)
    }

    fun warn(name: String, throwable: Throwable? = null) {
        val line = MelodyEventFormat.warnLine(name, throwable)
        runCatching { Log.println(Log.WARN, TAG, line) }
        sink?.invoke(line)
    }

    private fun emit(line: String) {
        runCatching { Log.println(Log.INFO, TAG, line) }
    }

    companion object {
        const val TAG: String = "BtRemixMelody"
        const val SIDE: String = "bridge"
    }
}

package com.Fusion.Btremix.melody.bridge

import com.Fusion.Btremix.melody.api.MelodyDiagnosticPolicy
import com.Fusion.Btremix.melody.api.MelodyEventFormat
import java.io.File

/**
 * BtRemix-side ring buffer for the host events forwarded by `IMelodyBridge.reportDiagnostics`
 * (M5.4 D-28/D-29).
 *
 * It is deliberately process-wide and dumb: the binder appends, the "导出 Melody 诊断" screen reads
 * and renders. Nothing here parses or interprets - the export is the logcat line format, so a human
 * reading the shared file and `adb logcat -s BtRemixMelody` see the same thing.
 */
object MelodyDiagnosticStore {

    private val lock = Any()
    private val events = ArrayDeque<String>()
    private var droppedCount = 0

    /**
     * Mirrors `melody_bridge.diagnostics_enabled` for the UI. The host reads the real preference at
     * process start; this copy only drives the switch's own state and the export header. Defaults to
     * off, matching the module default (M5.4 D-29 revised).
     */
    @Volatile
    var diagnosticsEnabled: Boolean = false

    fun record(name: String, fields: List<Pair<String, Any?>>) {
        val line = MelodyEventFormat.line(name, fields)
        synchronized(lock) {
            events.addLast(line)
            while (events.size > MelodyDiagnosticPolicy.MAX_EVENTS) {
                events.removeFirst()
                droppedCount += 1
            }
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { events.toList() }

    fun size(): Int = synchronized(lock) { events.size }

    /** Events that fell off the front of the ring buffer since the last [clear]. */
    fun dropped(): Int = synchronized(lock) { droppedCount }

    fun clear() {
        synchronized(lock) {
            events.clear()
            droppedCount = 0
        }
    }

    /** Renders the header block plus every buffered event, newest last. */
    fun render(header: Map<String, String?>): String = buildString {
        appendLine("# BtRemix Melody diagnostics")
        for ((key, value) in header) {
            append("# ").append(key).append(": ").append(value ?: "?").appendLine()
        }
        append("# events: ").append(size()).appendLine()
        append("# dropped: ").append(dropped()).appendLine()
        appendLine()
        synchronized(lock) { events.toList() }.forEach { appendLine(it) }
    }

    /** Best-effort dump to [file]; returns the file when it was written. */
    fun write(file: File, content: String = render(emptyMap())): File? = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(content)
        file
    }.getOrNull()
}

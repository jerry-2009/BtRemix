package com.fusion.melodyLinkNeo.melody.hook

import android.util.Log
import com.fusion.melodyLinkNeo.melody.api.MelodyDiagnosticPolicy
import com.fusion.melodyLinkNeo.melody.api.MelodyEventFormat
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

    /**
     * M5.4 D-29: when off, `event`/`detail` stop formatting and emitting (the load-time lines in
     * [MelodyDiagnosticPolicy.ALWAYS_PREFIXES] and every `warn` still go out). Off is written by the
     * BtRemix settings switch into the module's own `SharedPreferences`, so it takes effect at the
     * next host process start - same contract as `observation_enabled`.
     *
     * The default is **off** (the M5.4 review asked for it): a normal install pays nothing for
     * diagnostics, and the M5.5 matrix turns the switch on before collecting evidence.
     */
    @Volatile
    var diagnosticsEnabled: Boolean = false

    /**
     * M5.4 D-28: set once the host-side client exists; receives the events the policy forwards. The
     * sink must be non-blocking (the bridge call is `oneway`), and it is dropped on any failure.
     */
    @Volatile
    var diagnosticSink: ((String, List<Pair<String, Any?>>) -> Unit)? = null
        set(value) {
            field = value
            dropPending(value)
        }

    /**
     * Events the policy forwards that were emitted before the host-side client existed.
     *
     * The anchor hits happen during `onPackageLoaded`, while the client only appears once the host's
     * `Application.onCreate` runs - without this buffer the "锚点命中表" would always be empty in the
     * export. Bounded, and dropped wholesale when nobody ever attaches.
     */
    private val pending = ArrayDeque<Pair<String, List<Pair<String, Any?>>>>()

    private fun dropPending(sink: ((String, List<Pair<String, Any?>>) -> Unit)?) {
        if (sink == null) return
        val queued = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        for ((name, pairs) in queued) {
            runCatching { sink(name, pairs) }
        }
    }

    fun event(name: String, vararg pairs: Pair<String, Any?>) {
        if (!diagnosticsEnabled && MelodyDiagnosticPolicy.suppressedWhenDisabled(name)) return
        forward(name, pairs.toList())
        emit(Log.INFO, MelodyEventFormat.line(name, pairs.toList()))
    }

    /** One-line annotation for a payload that is too long for key/value pairs. */
    fun detail(name: String, message: String) {
        if (!diagnosticsEnabled) return
        emit(Log.INFO, MelodyEventFormat.detailLine(name, message))
    }

    fun warn(name: String, throwable: Throwable? = null) {
        emit(Log.WARN, MelodyEventFormat.warnLine(name, throwable))
    }

    private fun forward(name: String, pairs: List<Pair<String, Any?>>) {
        if (!diagnosticsEnabled || !MelodyDiagnosticPolicy.forwardsToBridge(name)) return
        val trimmed = pairs.take(MelodyDiagnosticPolicy.MAX_FIELDS)
        val sink = diagnosticSink
        if (sink == null) {
            synchronized(pending) {
                if (pending.size < PENDING_MAX) pending.addLast(name to trimmed)
            }
            return
        }
        runCatching {
            sink(name, trimmed)
        }.onFailure {
            // A failing diagnostic must never affect the host: log it (once per failure) and move on.
            emit(Log.WARN, MelodyEventFormat.warnLine("melody.diag.forward_failed", it))
        }
    }

    private fun emit(level: Int, message: String) {
        runCatching { Log.println(level, TAG, message) }
        runCatching { module?.log(level, TAG, message) }
    }

    companion object {
        const val TAG: String = "BtRemixMelody"

        /** Cap on events buffered before the bridge client exists (install-time anchor hits). */
        private const val PENDING_MAX: Int = 200

        /** `k=v` when the value is token-shaped, `k="v"` when it contains whitespace or quotes. */
        internal fun formatValue(value: Any?): String = MelodyEventFormat.formatValue(value)

        /** Collapses line breaks/tabs into spaces and bounds the length. */
        internal fun sanitize(text: String, maxChars: Int): String = MelodyEventFormat.sanitize(text, maxChars)

        internal fun describe(throwable: Throwable): String = MelodyEventFormat.describe(throwable)
    }
}

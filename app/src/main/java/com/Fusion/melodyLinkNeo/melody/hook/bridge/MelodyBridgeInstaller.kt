package com.fusion.melodyLinkNeo.melody.hook.bridge

import android.app.Application
import com.fusion.melodyLinkNeo.melody.hook.MelodyLog
import io.github.libxposed.api.XposedInterface

/**
 * Wires the Melody-side client into the host process (MELODY_BRIDGE_SPEC §12 M2b).
 *
 * The client needs a `Context`, and the stable way to get one from inside `com.oplus.melody` is the
 * same anchor M1 uses for its panel scan: `Application.onCreate`. Hooking it also guarantees the
 * service bind happens after the host process is usable, and the whole bootstrap is wrapped so a host
 * update that changes `onCreate` degrades to a `melody.anchor.missing` line rather than a crash.
 */
internal class MelodyBridgeInstaller(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val processName: String?,
) {

    fun install() {
        val onCreate = runCatching { Application::class.java.getMethod("onCreate") }.getOrNull()
        if (onCreate == null) {
            log.event("melody.anchor.missing", "hook" to "bridge.application")
            return
        }
        module.hook(onCreate).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            runCatching { onApplicationCreated(chain.thisObject as? Application) }
                .onFailure { log.warn("melody.bridge.bootstrap_failed", it) }
            result
        })
        log.event("melody.anchor.hooked", "hook" to "bridge.application")
    }

    private fun onApplicationCreated(app: Application?) {
        if (app == null) return
        val client = MelodyBridgeClients.getOrCreate(app, log)
        // M5.4 D-28: from here on, the events MelodyDiagnosticPolicy forwards travel to BtRemix over
        // the same binder the control path uses. A dead link just drops them (logcat still has them).
        log.diagnosticSink = { name, fields -> client.reportDiagnostics(name, fields) }
        MelodyBridgeClients.probeOnce(client, log, processName)
    }
}

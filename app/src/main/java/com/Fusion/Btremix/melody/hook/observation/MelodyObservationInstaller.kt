package com.Fusion.Btremix.melody.hook.observation

import com.Fusion.Btremix.melody.hook.MelodyLog
import io.github.libxposed.api.XposedInterface

/**
 * Installs every M1 read-only observation hook (MELODY_BRIDGE_SPEC §12 M1).
 *
 * The hooks are installed in every `com.oplus.melody` process. The two processes have different
 * jobs — providers, the device registry and the command receivers live in the main process while all
 * Activities live in `:fg` — but the classes are present in both, and a hook that never fires costs
 * nothing. Each installer resolves its own anchors and reports `melody.anchor.missing` instead of
 * failing the group, so a host update that renames one surface still leaves the others observable.
 */
internal class MelodyObservationInstaller(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
    private val processName: String?,
) {

    fun install() {
        log.event("melody.observation.install", "process" to processName)
        installQuietly("support") { SupportObservationHook(module, log, loader).install() }
        installQuietly("deviceinfo") { DeviceInfoObservationHook(module, log, loader).install() }
        installQuietly("command") { CommandObservationHook(module, log, loader).install() }
        installQuietly("panel") { PanelObservationHook(module, log, loader).install() }
    }

    private inline fun installQuietly(name: String, block: () -> Unit) {
        runCatching(block).onFailure { log.warn("melody.observation.$name.failed", it) }
    }
}

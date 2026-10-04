package com.Fusion.Btremix.melody.hook.injection

import com.Fusion.Btremix.melody.hook.MelodyLog
import io.github.libxposed.api.XposedInterface

/**
 * Installs the M3.3 provider injection (`HANDOFF_MELODY_M3_PLAN.md` §4 M3.3).
 *
 * Deliberately separate from [com.Fusion.Btremix.melody.hook.observation.MelodyObservationInstaller]
 * and behind its own `melody_bridge.injection_enabled` switch (M3-D5): observation stays read-only and
 * can be on while injection is off, and a broken injection anchor degrades to one log line instead of
 * touching the observation path.
 *
 * Runs in every `com.oplus.melody` process; the provider lives in the main process, and installing the
 * hook in `:fg` too is harmless (the anchor never fires there).
 */
internal class MelodyInjectionInstaller(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
    private val processName: String?,
    /** Host `ApplicationInfo.sourceDir`; the DexKit fallback for the transport anchors needs it. */
    private val hostApkPath: String?,
) {

    fun install() {
        log.event("melody.injection.install", "process" to processName)
        runCatching { MelodyAliveProviderInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.failed", it) }
        // M3.4: the registry the detail page is actually built from, then the two transport backstops.
        runCatching { MelodyDeviceInfoInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.deviceinfo_failed", it) }
        runCatching { MelodyTransportInjection(module, log, loader, hostApkPath).install() }
            .onFailure { log.warn("melody.injection.transport_failed", it) }
        // M3.4b: the in-memory whitelist repository the device-centre card code asks (the Provider
        // injection alone cannot reach it, see MelodyWhitelistRepositoryInjection).
        runCatching { MelodyWhitelistRepositoryInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.whitelist_repo_failed", it) }
        // M4.2: hide/grey the official detail-page rows the Definition asked to remove. Read-only with
        // respect to the official data - it only flips `setVisible`/`setEnabled` from the envelope policy.
        runCatching { MelodyPanelInjection(module, log, loader).install() }
            .onFailure { log.warn("melody.injection.panel_failed", it) }
    }
}

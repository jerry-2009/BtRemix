package com.Fusion.Btremix.melody.hook

import com.Fusion.Btremix.melody.hook.observation.MelodyObservationInstaller
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 101 entry point for the Melody bridge (MELODY_BRIDGE_SPEC §11.1).
 *
 * The framework instantiates this class once per injected process generation. M0 only proved that the
 * module is installed, scoped and loaded; since M1 the host branch also installs the read-only
 * observation hooks. Every installer is wrapped in `runCatching`, so a host update that removes an
 * anchor degrades to a missing-anchor log line instead of a crash inside `com.oplus.melody`.
 *
 * Injection remains opt-out through the LSPosed module preferences (`melody_bridge.observation_enabled`,
 * default enabled); when the remote preference pipe is unavailable the default keeps observation on,
 * matching the M0 "never leave the user without diagnostics" behaviour.
 */
class MelodyBridgeEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        val log = MelodyLog(this)
        log.event(
            "melody.scope.loaded",
            "stage" to "module",
            "process" to param.processName,
            "system_server" to param.isSystemServer,
        )
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val log = MelodyLog(this)
        // scope.list only lists com.oplus.melody, but guard anyway so an over-broad scope never
        // installs anything by accident.
        val packageName = runCatching { param.packageName }.getOrNull()
        if (packageName != HOST_PACKAGE) {
            log.event("melody.scope.skipped", "package" to packageName)
            return
        }
        val processName = runCatching { param.applicationInfo.processName }.getOrNull()
        log.event(
            "melody.scope.loaded",
            "stage" to "package",
            "package" to packageName,
            "process" to processName,
            "first_package" to runCatching { param.isFirstPackage }.getOrNull(),
        )
        if (!observationEnabled(log)) {
            log.event("melody.observation.disabled", "process" to processName)
            return
        }
        runCatching {
            MelodyObservationInstaller(
                module = this,
                log = log,
                loader = param.defaultClassLoader,
                processName = processName,
            ).install()
        }.onFailure { log.warn("melody.observation.install_failed", it) }
    }

    /**
     * Reads the module preference pipe. Defaults to enabled on any error so a flaky framework state
     * never silently disables the M1 observation that the milestone is validated with.
     */
    private fun observationEnabled(log: MelodyLog): Boolean = runCatching {
        getRemotePreferences(MODULE_PREFS).getBoolean(KEY_OBSERVATION_ENABLED, true)
    }.getOrElse {
        log.warn("melody.observation.prefs_unavailable", it)
        true
    }

    companion object {
        /** logcat tag used by the module; `logcat -s BtRemixMelody` shows the load events. */
        const val TAG: String = MelodyLog.TAG

        const val HOST_PACKAGE: String = "com.oplus.melody"

        const val MODULE_PREFS: String = "melody_bridge"
        const val KEY_OBSERVATION_ENABLED: String = "observation_enabled"
    }
}

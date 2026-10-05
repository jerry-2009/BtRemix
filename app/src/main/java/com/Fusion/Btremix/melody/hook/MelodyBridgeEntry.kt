package com.Fusion.Btremix.melody.hook

import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeInstaller
import com.Fusion.Btremix.melody.hook.injection.MelodyInjectionInstaller
import com.Fusion.Btremix.melody.hook.observation.MelodyObservationInstaller
import com.Fusion.Btremix.melody.hook.settings.MelodyWirelessSettingsInjection
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
 * The switch set grew with the milestones: `melody_bridge.observation_enabled` (M1, default on) and
 * `melody_bridge.bridge_enabled` (M2b, default on) are opt-out diagnostics, while
 * `melody_bridge.injection_enabled` (M3.3, default off) is the opt-in that synthesises whitelist rows.
 * When the remote preference pipe is unavailable observation/bridge stay on and injection stays off.
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
        val packageName = runCatching { param.packageName }.getOrNull()
        // The scope is a fixed list (scope.list); anything else - an over-broad scope, or the module
        // being enabled for a system package - must not install a branch by accident.
        if (packageName != HOST_PACKAGE && packageName != SETTINGS_PACKAGE) {
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
        if (packageName == SETTINGS_PACKAGE) {
            installSettingsBranch(log, param.defaultClassLoader, processName)
            return
        }
        // M5.4 D-26: debug-only overrides for the host version gate, so the fail branch can be
        // exercised on a real device without a substitute APK. Both default to empty (= no override).
        MelodyHostGateOverrides.range = modulePref(log, KEY_HOST_VERSIONS_OVERRIDE)
        MelodyHostGateOverrides.version = modulePref(log, KEY_HOST_VERSION_OVERRIDE)
        // M5.4 D-29 (2026-10-05 revised): the diagnostics switch defaults OFF - the M5.5 matrix turns
        // it on explicitly, and normal use pays nothing for the forwarding.
        log.diagnosticsEnabled = diagnosticsEnabled(log)
        if (!observationEnabled(log)) {
            log.event("melody.observation.disabled", "process" to processName)
        } else {
            runCatching {
                MelodyObservationInstaller(
                    module = this,
                    log = log,
                    loader = param.defaultClassLoader,
                    processName = processName,
                ).install()
            }.onFailure { log.warn("melody.observation.install_failed", it) }
        }
        val bridge = bridgeEnabled(log)
        if (!bridge) {
            log.event("melody.bridge.disabled", "process" to processName)
        } else {
            runCatching {
                MelodyBridgeInstaller(module = this, log = log, processName = processName).install()
            }.onFailure { log.warn("melody.bridge.install_failed", it) }
        }
        // M3.3/M3.4: while the module is enabled and scoped to the host, the projection hooks are
        // always installed - there is no separate "injection" switch (decision M3-D5, revised
        // 2026-10-04: "模块开着就要一直 hook"). The hooks still need the bridge for the projection
        // envelopes; without it (or before it connects) they simply find no data and stay inert.
        runCatching {
            MelodyInjectionInstaller(
                module = this,
                log = log,
                loader = param.defaultClassLoader,
                processName = processName,
                hostApkPath = runCatching { param.applicationInfo.sourceDir }.getOrNull(),
            ).install()
        }.onFailure { log.warn("melody.injection.install_failed", it) }
    }

    /**
     * `com.oplus.wirelesssettings` branch (M3.4c): the Bluetooth device detail page is rendered by the
     * settings app, not by Melody, so its "耳机功能" row can only be kept alive from inside that process
     * (see [MelodyWirelessSettingsInjection]).
     *
     * Only this one hook is installed here - no observation, no doorbell, no provider injection: the
     * settings process has no need for the bridge, and its own whitelist copy is what the hook reads.
     */
    private fun installSettingsBranch(
        log: MelodyLog,
        loader: ClassLoader,
        processName: String?,
    ) {
        log.event("melody.settings.branch", "process" to processName)
        runCatching {
            MelodyWirelessSettingsInjection(module = this, log = log, loader = loader).install()
        }.onFailure { log.warn("melody.settings.install_failed", it) }
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

    /**
     * M2b link switch; defaults to enabled for the same reason observation does. Turning it off leaves the
     * module installed but stops this process from registering the doorbell receiver, so it keeps using the
     * cold-start cache instead of the live bridge (the service itself is started by BtRemix, not by us).
     */
    private fun bridgeEnabled(log: MelodyLog): Boolean = runCatching {
        getRemotePreferences(MODULE_PREFS).getBoolean(KEY_BRIDGE_ENABLED, true)
    }.getOrElse {
        log.warn("melody.bridge.prefs_unavailable", it)
        true
    }

    /** M5.4 D-29; defaults off, so collecting evidence in M5.5 is an explicit opt-in. */
    private fun diagnosticsEnabled(log: MelodyLog): Boolean = runCatching {
        getRemotePreferences(MODULE_PREFS).getBoolean(KEY_DIAGNOSTICS_ENABLED, false)
    }.getOrElse {
        log.warn("melody.diag.prefs_unavailable", it)
        false
    }

    /** A string preference from the module pipe; `null` (no override) on any error. */
    private fun modulePref(log: MelodyLog, key: String): String? = runCatching {
        getRemotePreferences(MODULE_PREFS).getString(key, null)?.trim()?.ifEmpty { null }
    }.getOrElse {
        log.warn("melody.prefs.string_unavailable", it)
        null
    }

    companion object {
        /** logcat tag used by the module; `logcat -s BtRemixMelody` shows the load events. */
        const val TAG: String = MelodyLog.TAG

        const val HOST_PACKAGE: String = "com.oplus.melody"

        /** Declared in `scope.list` from M3.4c onwards; the Bluetooth detail page lives here. */
        const val SETTINGS_PACKAGE: String = "com.oplus.wirelesssettings"

        /** Module preference group; only the observation/bridge diagnostics are still switchable. */
        const val MODULE_PREFS: String = "melody_bridge"
        const val KEY_OBSERVATION_ENABLED: String = "observation_enabled"
        const val KEY_BRIDGE_ENABLED: String = "bridge_enabled"

        /** M5.4 D-26 debug overrides; empty by default, see [MelodyHostGateOverrides]. */
        const val KEY_HOST_VERSIONS_OVERRIDE: String = "host_versions_override"
        const val KEY_HOST_VERSION_OVERRIDE: String = "host_version_override"

        /** M5.4 D-29 diagnostics switch; written by the BtRemix "Melody 诊断" screen. */
        const val KEY_DIAGNOSTICS_ENABLED: String = "diagnostics_enabled"
    }
}

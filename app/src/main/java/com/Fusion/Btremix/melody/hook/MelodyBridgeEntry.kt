package com.Fusion.Btremix.melody.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 101 entry point for the Melody bridge (MELODY_BRIDGE_SPEC §11.1, milestone M0).
 *
 * The framework instantiates this class once per injected process generation. M0 deliberately does
 * nothing but prove that the module is installed, scoped and loaded: it emits the
 * `melody.scope.loaded` event and never touches the host. Every hook installer (support injection,
 * panel projection, transport suppression) is added in M1+ and hangs off this entry point.
 *
 * The class must stay a small, no-argument, always-safe entry: a failure here would otherwise
 * surface as a crash inside `com.oplus.melody`, so all logging is defensive.
 */
class MelodyBridgeEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        event(
            "melody.scope.loaded",
            "stage" to "module",
            "process" to param.processName,
            "system_server" to param.isSystemServer,
        )
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // scope.list only lists com.oplus.melody, but guard anyway so an over-broad scope never
        // installs anything by accident.
        val packageName = runCatching { param.packageName }.getOrNull()
        if (packageName != HOST_PACKAGE) {
            event("melody.scope.skipped", "package" to packageName)
            return
        }
        event(
            "melody.scope.loaded",
            "stage" to "package",
            "package" to packageName,
            "process" to runCatching { param.applicationInfo.processName }.getOrNull(),
            "first_package" to runCatching { param.isFirstPackage }.getOrNull(),
        )
    }

    /**
     * Emits one structured `evt=<name> k=v` line to logcat and, when available, to the LSPosed
     * module log. Never throws: the host process must not be affected by our diagnostics.
     */
    private fun event(name: String, vararg pairs: Pair<String, Any?>) {
        val message = buildString {
            append("evt=").append(name)
            for ((key, value) in pairs) append(' ').append(key).append('=').append(value)
        }
        runCatching { Log.println(Log.INFO, TAG, message) }
        runCatching { log(Log.INFO, TAG, message) }
    }

    companion object {
        /** logcat tag used by the module; `logcat -s BtRemixMelody` shows the load events. */
        const val TAG: String = "BtRemixMelody"

        const val HOST_PACKAGE: String = "com.oplus.melody"
    }
}

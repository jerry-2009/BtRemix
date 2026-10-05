package com.fusion.melodyLinkNeo.melody.bridge

import android.content.Context

/**
 * The module's own `melody_bridge.diagnostics_enabled` switch (M5.4 D-29).
 *
 * The injected host reads the real preference at its next process start; the app-side copy exists so
 * the Settings → 开发者 switch and the export header agree. File/key names mirror
 * `MelodyBridgeEntry.MODULE_PREFS` / `KEY_DIAGNOSTICS_ENABLED` on the hook side.
 */
object MelodyDiagnosticsPrefs {
    const val FILE: String = "melody_bridge"
    const val KEY_ENABLED: String = "diagnostics_enabled"

    fun read(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** Persists [enabled] and updates the in-memory mirror the export header reads. */
    fun write(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        MelodyDiagnosticStore.diagnosticsEnabled = enabled
    }
}

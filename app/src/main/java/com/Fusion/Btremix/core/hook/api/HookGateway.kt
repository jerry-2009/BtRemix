package com.Fusion.Btremix.core.hook.api

import java.time.Instant
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing view of the hook module (D-UI-6, DEVICE_CENTER_UI_PLAN §3.2).
 *
 * The UI and its ViewModels depend only on this package; the single implementation lives in
 * `melody.bridge` and adapts the host-update tracker + anchor report. The interface is deliberately
 * minimal: it exists so that the `ui` package never imports `melody`.
 */

/** Four-state module health (DEVICE_CENTER_UI_PLAN §3.3). */
enum class ModuleStatus { RUNNING, WARNING, ERROR, STOPPED }

/** Anchor resolution coverage as reported by the injected host process. */
data class HookAnchorCoverage(val hits: Int, val total: Int) {
    val ratio: Float get() = if (total <= 0) 0f else hits.toFloat() / total.toFloat()
}

/** Everything the UI needs to render the module card, reduced to neutral terms. */
data class HookGatewayState(
    val hostInstalled: Boolean = false,
    val version: String? = null,
    /** True while the host has not yet reported anchors for the installed build. */
    val awaitingHost: Boolean = false,
    val updateDetected: Boolean = false,
    val coverage: HookAnchorCoverage = HookAnchorCoverage(0, 0),
    val missingAnchorIds: List<String> = emptyList(),
    val lastReportAt: Instant? = null,
)

sealed interface HookEvent {
    data class StateChanged(val state: HookGatewayState) : HookEvent
    data class AnchorReported(val coverage: HookAnchorCoverage) : HookEvent
}

interface HookGateway {
    val state: StateFlow<HookGatewayState>
    val events: SharedFlow<HookEvent>

    /** Mirrors the module's own `diagnostics_enabled` switch (Settings → 开发者). */
    val diagnosticsEnabled: StateFlow<Boolean>

    /** Re-reads the host install / anchor report. Safe to call from the UI on refresh. */
    fun refresh()

    /** Marks a pending host-update prompt as seen so the in-app banner stops showing it. */
    fun acknowledgeUpdate()

    /**
     * Drops the persisted anchor cache so the next host start re-resolves every anchor with DexKit.
     * The UI exposes this as the "Dex 适配" action.
     */
    fun requestDexRescan()

    /** Writes the module's diagnostics switch; the host reads it at its next process start. */
    fun setDiagnosticsEnabled(enabled: Boolean)
}

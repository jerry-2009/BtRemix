package com.fusion.melodyLinkNeo.melody.bridge

import android.content.Context
import com.fusion.melodyLinkNeo.core.hook.api.HookAnchorCoverage
import com.fusion.melodyLinkNeo.core.hook.api.HookEvent
import com.fusion.melodyLinkNeo.core.hook.api.HookGateway
import com.fusion.melodyLinkNeo.core.hook.api.HookGatewayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The single [HookGateway] implementation (D-UI-6): Melody is the only hook target
 * (DEVICE_CENTER_UI_PLAN §3.2).
 *
 * Melody's own tracking already publishes a `StateFlow<MelodyHostUpdateState?>`; this adapter only
 * renames its concepts into the neutral `core.hook.api` vocabulary, so the product UI depends on
 * `core.hook.api` rather than on Melody. `refresh()` delegates to the tracker, which re-reads the host `versionName` and the
 * persisted anchor report.
 */
class MelodyHookGateway(
    private val context: Context,
    scope: CoroutineScope,
) : HookGateway {

    private val mutableState = MutableStateFlow(HookGatewayState())
    override val state: StateFlow<HookGatewayState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<HookEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<HookEvent> = mutableEvents.asSharedFlow()

    private val mutableDiagnostics = MutableStateFlow(MelodyDiagnosticsPrefs.read(context))
    override val diagnosticsEnabled: StateFlow<Boolean> = mutableDiagnostics.asStateFlow()

    init {
        // Keep the export header's in-memory mirror in step with the persisted switch on process start.
        MelodyDiagnosticStore.diagnosticsEnabled = mutableDiagnostics.value
        scope.launch {
            MelodyHostUpdateTracker.state.collect { update -> publish(update) }
        }
    }

    override fun refresh() {
        runCatching { MelodyHostUpdateTracker.refresh(context.applicationContext) }
    }

    override fun acknowledgeUpdate() {
        runCatching { MelodyHostUpdateTracker.acknowledge(context.applicationContext) }
    }

    override fun requestDexRescan() {
        runCatching { MelodyHostUpdateTracker.requestRescan(context.applicationContext) }
    }

    override fun setDiagnosticsEnabled(enabled: Boolean) {
        MelodyDiagnosticsPrefs.write(context.applicationContext, enabled)
        mutableDiagnostics.value = enabled
    }

    private fun publish(update: MelodyHostUpdateState?) {
        val next = update.toGatewayState()
        val previous = mutableState.value
        mutableState.value = next
        if (next != previous) {
            mutableEvents.tryEmit(HookEvent.StateChanged(next))
            mutableEvents.tryEmit(HookEvent.AnchorReported(next.coverage))
        }
    }
}

/** Pure translation of the Melody tracker snapshot into the neutral gateway state. */
internal fun MelodyHostUpdateState?.toGatewayState(): HookGatewayState {
    if (this == null) return HookGatewayState()
    return HookGatewayState(
        hostInstalled = hostInstalled,
        version = version,
        awaitingHost = awaitingHost,
        updateDetected = updateDetected,
        coverage = HookAnchorCoverage(hits = hits, total = total),
        missingAnchorIds = missingIds,
    )
}

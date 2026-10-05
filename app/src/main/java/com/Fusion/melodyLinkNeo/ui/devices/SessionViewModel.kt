package com.fusion.melodyLinkNeo.ui.devices

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fusion.melodyLinkNeo.BtRemixApplication
import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.device.runtime.ActionResult
import com.fusion.melodyLinkNeo.device.runtime.DeviceAction
import com.fusion.melodyLinkNeo.device.runtime.DeviceEvent
import com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState
import com.fusion.melodyLinkNeo.device.runtime.ProtocolSession
import com.fusion.melodyLinkNeo.device.runtime.StateEntry
import com.fusion.melodyLinkNeo.device.session.DeviceSessionOpener
import com.fusion.melodyLinkNeo.device.session.SessionRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SessionUiState(
    val mac: String = "",
    val name: String? = null,
    val packageDisplayName: String? = null,
    val author: String? = null,
    val deviceType: String? = null,
    val definition: LoadedDeviceDefinition? = null,
    val lifecycle: DeviceLifecycleState = DeviceLifecycleState.Created,
    val state: Map<String, StateEntry> = emptyMap(),
    val elapsedSeconds: Long = 0,
    val recentEvents: List<String> = emptyList(),
    val pendingActionId: String? = null,
    val actionError: String? = null,
    val error: String? = null,
    val opening: Boolean = true,
)

/**
 * Device Session (DEVICE_CENTER_UI_PLAN §5.3, D-UI-3).
 *
 * Acquires the shared session on entry ([SessionRegistry.acquire]) and releases it on exit unless
 * "后台运行" is on, in which case an app-scoped hold keeps the connection alive. The control area
 * reuses `DefinitionRenderer`, so only the existing nine UI node types are supported this milestone.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BtRemixApplication
    private val opener = DeviceSessionOpener(
        ble = app.bleRepository,
        rfcomm = app.rfcomm,
        runtime = app.deviceRuntime,
        sessionFactory = app.sessionFactory,
        packages = app.packages.registry,
    )

    private val mutableState = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()

    private var session: ProtocolSession? = null
    private var currentMac: String? = null
    private var startedAtMillis: Long = 0
    private val collectors = mutableListOf<Job>()
    private var timerJob: Job? = null

    fun open(mac: String, name: String? = null) {
        val normalized = SessionRegistry.normalize(mac)
        if (session != null && currentMac == normalized) {
            startCollecting()
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(opening = true, error = null) }
            val entry = app.deviceRegistry.devices.value.firstOrNull { it.key == normalized }
            val definition = entry?.let { app.packages.registry.find(it.packageId)?.definition }
            val displayName = name ?: entry?.name
            app.activity.registerDeviceName(normalized, displayName ?: entry?.packageDisplayName)
            runCatching {
                app.sessionManager.noteHeld(normalized)
                opener.acquire(
                    registry = app.sessions,
                    mac = normalized,
                    name = displayName,
                    definition = definition,
                )
            }.onSuccess { active ->
                session = active
                currentMac = normalized
                startedAtMillis = System.currentTimeMillis()
                mutableState.update {
                    it.copy(
                        mac = normalized,
                        name = displayName,
                        packageDisplayName = entry?.packageDisplayName,
                        author = entry?.author,
                        deviceType = entry?.deviceType,
                        definition = definition,
                        lifecycle = active.lifecycle.value,
                        state = active.state.entries.value,
                        opening = false,
                        error = null,
                    )
                }
                startCollecting()
            }.onFailure { failure ->
                mutableState.update { it.copy(opening = false, error = failure.message ?: "无法建立会话") }
            }
        }
    }

    fun execute(action: DeviceAction) {
        val active = session ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(pendingActionId = action.id, actionError = null) }
            when (val result = active.execute(action)) {
                is ActionResult.Success -> mutableState.update { it.copy(pendingActionId = null) }
                is ActionResult.Failure -> mutableState.update {
                    it.copy(pendingActionId = null, actionError = result.error.message)
                }
            }
        }
    }

    fun clearActionError() = mutableState.update { it.copy(actionError = null) }

    fun retry() {
        val mac = mutableState.value.mac
        session = null
        currentMac = null
        if (mac.isNotBlank()) open(mac, mutableState.value.name)
    }

    /**
     * Called when the screen leaves. With [backgroundRun] the connection is kept alive through an
     * app-scoped hold; otherwise the reference is released immediately.
     */
    fun leave(backgroundRun: Boolean) {
        cancelCollectors()
        val mac = currentMac ?: return
        if (backgroundRun) {
            app.sessionManager.hold(mac)
        } else {
            app.sessionManager.noteReleased(mac)
            app.sessions.releaseAsync(mac)
        }
        session = null
        currentMac = null
    }

    override fun onCleared() {
        super.onCleared()
        // Safety net: a ViewModel destroyed without an explicit leave() must not leak its reference.
        cancelCollectors()
        val mac = currentMac
        if (mac != null) {
            app.sessions.releaseAsync(mac)
            app.sessionManager.noteReleased(mac)
        }
        session = null
        currentMac = null
    }

    private fun startCollecting() {
        val active = session ?: return
        cancelCollectors()
        collectors += viewModelScope.launch {
            active.lifecycle.collect { lifecycle -> mutableState.update { it.copy(lifecycle = lifecycle) } }
        }
        collectors += viewModelScope.launch {
            active.state.entries.collect { entries -> mutableState.update { it.copy(state = entries) } }
        }
        collectors += viewModelScope.launch {
            active.events.collect { event -> onEvent(event) }
        }
        timerJob = viewModelScope.launch {
            while (isActive) {
                mutableState.update { it.copy(elapsedSeconds = (System.currentTimeMillis() - startedAtMillis) / 1000) }
                delay(1_000)
            }
        }
    }

    private fun cancelCollectors() {
        collectors.forEach(Job::cancel)
        collectors.clear()
        timerJob?.cancel()
        timerJob = null
    }

    private fun onEvent(event: DeviceEvent) {
        val mac = currentMac
        if (mac != null) app.activity.recordEvent(mac, event)
        val line = when (event) {
            is DeviceEvent.LifecycleChanged -> "生命周期 ${event.previous::class.simpleName} → ${event.current::class.simpleName}"
            is DeviceEvent.ActionStarted -> "动作开始 ${event.action.id}"
            is DeviceEvent.ActionCompleted -> "动作完成 ${event.action.id}"
            is DeviceEvent.ActionFailed -> "动作失败 ${event.action.id}: ${event.error.message}"
            is DeviceEvent.StateChanged -> "状态 ${event.key} 更新"
            is DeviceEvent.Error -> "错误 ${event.error.message}"
            else -> null
        } ?: return
        mutableState.update { it.copy(recentEvents = (listOf(line) + it.recentEvents).take(EVENT_LIMIT)) }
    }

    private companion object {
        const val EVENT_LIMIT = 20
    }
}

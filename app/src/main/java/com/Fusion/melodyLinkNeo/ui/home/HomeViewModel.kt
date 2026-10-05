package com.fusion.melodyLinkNeo.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fusion.melodyLinkNeo.BtRemixApplication
import com.fusion.melodyLinkNeo.core.activity.ActivityEntry
import com.fusion.melodyLinkNeo.core.hook.ModuleStatusUi
import com.fusion.melodyLinkNeo.device.registry.DeviceEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val loading: Boolean = true,
    val moduleStatus: ModuleStatusUi? = null,
    val activeSessions: Int = 0,
    val connectedDevices: Int = 0,
    val installedPackages: Int = 0,
    val enabledPackages: Int = 0,
    val activities: List<ActivityEntry> = emptyList(),
    val devices: List<DeviceEntry> = emptyList(),
)

/**
 * Home aggregates the four process-scoped sources named in DEVICE_CENTER_UI_PLAN §5.1:
 * module status, session manager, device registry and package repository, plus the activity stream.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BtRemixApplication

    val state: StateFlow<HomeUiState> = combine(
        app.moduleStatus.status,
        app.sessionManager.activeSessionCount,
        app.deviceRegistry.devices,
        app.packageRepository.packages,
        app.activity.entries,
    ) { module, sessions, devices, packages, activities ->
        HomeUiState(
            loading = false,
            moduleStatus = module,
            activeSessions = sessions,
            connectedDevices = devices.count { it.connected },
            installedPackages = packages.size,
            enabledPackages = packages.count { it.enabled },
            activities = activities.take(ACTIVITY_LIMIT),
            devices = devices,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun refresh() {
        app.moduleStatus.refresh()
        app.deviceRegistry.refresh()
    }

    private companion object {
        const val ACTIVITY_LIMIT = 8
    }
}

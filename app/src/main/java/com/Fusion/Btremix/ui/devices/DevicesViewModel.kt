package com.Fusion.Btremix.ui.devices

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.Fusion.Btremix.BtRemixApplication
import com.Fusion.Btremix.core.permissions.AndroidBluetoothPermissionManager
import com.Fusion.Btremix.device.registry.DeviceEntry
import com.Fusion.Btremix.device.session.SessionRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DeviceFilter(val label: String) {
    ALL("全部"),
    CONNECTED("已连接"),
}

data class DevicesUiState(
    val filter: DeviceFilter = DeviceFilter.ALL,
    val devices: List<DeviceEntry> = emptyList(),
    val scanning: Boolean = false,
    val permissionGranted: Boolean = false,
    val pendingRoute: String? = null,
    val error: String? = null,
)

/**
 * Devices page state (DEVICE_CENTER_UI_PLAN §5.2).
 *
 * The list comes from [com.Fusion.Btremix.device.registry.DeviceRegistry], so installed but
 * currently disconnected devices stay visible; the filter is intentionally limited to 全部/已连接 in
 * this milestone.
 */
class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BtRemixApplication
    private val permissionManager = AndroidBluetoothPermissionManager()

    private val local = MutableStateFlow(DevicesUiState(permissionGranted = false))

    val state: StateFlow<DevicesUiState> = combine(app.deviceRegistry.devices, local) { devices, ui ->
        val filtered = when (ui.filter) {
            DeviceFilter.ALL -> devices
            DeviceFilter.CONNECTED -> devices.filter { it.connected }
        }
        ui.copy(devices = filtered)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    fun requiredPermissions(): Array<String> =
        permissionManager.requiredPermissions(android.os.Build.VERSION.SDK_INT).toTypedArray()

    fun updatePermission(granted: Boolean) = local.update { it.copy(permissionGranted = granted) }

    fun setFilter(filter: DeviceFilter) = local.update { it.copy(filter = filter) }

    fun refresh() {
        app.deviceRegistry.refresh()
        local.update { it.copy(scanning = false) }
    }

    fun toggleScan() {
        if (local.value.scanning) stopScan() else startScan()
    }

    private fun startScan() {
        if (!local.value.permissionGranted) {
            local.update { it.copy(error = "需要蓝牙权限才能扫描设备") }
            return
        }
        (app.deviceDiscovery as? com.Fusion.Btremix.device.registry.AndroidDeviceDiscoverySource)?.startBleScan()
        local.update { it.copy(scanning = true, error = null) }
    }

    private fun stopScan() {
        (app.deviceDiscovery as? com.Fusion.Btremix.device.registry.AndroidDeviceDiscoverySource)?.stopBleScan()
        local.update { it.copy(scanning = false) }
    }

    fun connect(entry: DeviceEntry) {
        if (!local.value.permissionGranted) {
            local.update { it.copy(error = "需要蓝牙权限才能连接设备") }
            return
        }
        // D-UI-3: the session screen owns acquire/release, so tapping a card only navigates.
        stopScan()
        app.activity.registerDeviceName(SessionRegistry.normalize(entry.mac), entry.name ?: entry.packageDisplayName)
        local.update { it.copy(pendingRoute = entry.mac, error = null) }
    }

    fun consumeRoute() = local.update { it.copy(pendingRoute = null) }

    fun dismissError() = local.update { it.copy(error = null) }
}

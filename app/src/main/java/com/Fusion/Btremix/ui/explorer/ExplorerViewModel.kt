package com.Fusion.Btremix.ui.explorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.Fusion.Btremix.core.bluetooth.BleRepository
import com.Fusion.Btremix.core.bluetooth.android.AndroidBleManager
import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.core.bluetooth.api.BleService
import com.Fusion.Btremix.core.bluetooth.api.ConnectionState
import com.Fusion.Btremix.core.logging.InMemoryLogger
import com.Fusion.Btremix.core.logging.LogCategory
import com.Fusion.Btremix.core.logging.LogEntry
import com.Fusion.Btremix.core.permissions.AndroidBluetoothPermissionManager
import com.Fusion.Btremix.core.permissions.PermissionStatus
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.device.runtime.DeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExplorerUiState(
    val devices: List<BleScanResult> = emptyList(),
    val scanning: Boolean = false,
    val connectedDevice: BleDevice? = null,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val services: List<BleService> = emptyList(),
    val values: Map<String, ByteArray> = emptyMap(),
    val notifying: Set<String> = emptySet(),
    val logs: List<LogEntry> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val permissionGranted: Boolean = false,
)

class ExplorerViewModel(application: Application) : AndroidViewModel(application) {
    private val logger = InMemoryLogger()
    private val repository = BleRepository(AndroidBleManager(application), logger)
    private val permissionManager = AndroidBluetoothPermissionManager()
    private val deviceRuntime: DeviceRuntime = DefaultDeviceRuntime()
    private val mutableState = MutableStateFlow(ExplorerUiState())
    val state = mutableState.asStateFlow()
    private var session: DeviceSession? = null
    private var scanJob: Job? = null
    private var connectionStateJob: Job? = null
    private val notificationJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            repository.logs.collect { logs -> mutableState.update { it.copy(logs = logs) } }
        }
    }

    fun updatePermissionStatus(isGranted: (String) -> Boolean) {
        val granted = permissionManager.status(android.os.Build.VERSION.SDK_INT, isGranted) == PermissionStatus.Granted
        mutableState.update { it.copy(permissionGranted = granted, error = if (granted) null else it.error) }
    }

    fun requiredPermissions(): Array<String> = permissionManager
        .requiredPermissions(android.os.Build.VERSION.SDK_INT)
        .toTypedArray()

    fun toggleScan() {
        if (mutableState.value.scanning) stopScan() else startScan()
    }

    private fun startScan() {
        if (!mutableState.value.permissionGranted) {
            mutableState.update { it.copy(error = "Bluetooth permissions are required to scan") }
            return
        }
        scanJob?.cancel()
        mutableState.update { it.copy(scanning = true, devices = emptyList(), error = null) }
        scanJob = viewModelScope.launch {
            repository.scan().catch { error ->
                mutableState.update { it.copy(scanning = false, error = error.message ?: "BLE scan failed") }
                repository.log(LogCategory.ERROR, "Scan failed: ${error.message}")
            }.collect { result ->
                mutableState.update { current ->
                    val devices = (current.devices.filterNot { it.device.id == result.device.id } + result)
                        .sortedByDescending { it.rssi }
                    current.copy(devices = devices)
                }
                repository.log(
                    LogCategory.SCAN,
                    "${result.device.name ?: result.device.address}, RSSI ${result.rssi} dBm",
                    result.device.id,
                )
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        viewModelScope.launch { runCatching { repository.stopScan() } }
        mutableState.update { it.copy(scanning = false) }
    }

    fun connect(device: BleDevice) {
        if (!mutableState.value.permissionGranted) {
            mutableState.update { it.copy(error = "Bluetooth permissions are required to connect") }
            return
        }
        viewModelScope.launch {
            stopScan()
            disconnect()
            mutableState.update {
                it.copy(connectedDevice = device, connectionState = ConnectionState.Connecting, services = emptyList(), error = null)
            }
            runCatching {
                val connection = repository.connect(device)
                val active = deviceRuntime.open(device, connection)
                session = active
                connectionStateJob?.cancel()
                connectionStateJob = launch {
                    active.lifecycle.collect { lifecycle ->
                        mutableState.update {
                            it.copy(
                                connectionState = lifecycle.toConnectionState(),
                                services = active.services.value,
                            )
                        }
                    }
                }
                val services = active.services.value
                mutableState.update { it.copy(services = services, connectionState = ConnectionState.Ready) }
                repository.log(LogCategory.GATT, "Discovered ${services.size} services", device.id)
            }.onFailure { error ->
                mutableState.update {
                    it.copy(connectionState = ConnectionState.Error(com.Fusion.Btremix.core.bluetooth.api.BleError.Unknown(error.message ?: "Connection failed", error)), error = error.message ?: "Connection failed")
                }
                repository.log(LogCategory.ERROR, "Connection failed: ${error.message}", device.id)
            }
        }
    }

    fun disconnect() {
        notificationJobs.values.forEach(Job::cancel)
        notificationJobs.clear()
        connectionStateJob?.cancel()
        val active = session
        session = null
        if (active != null) viewModelScope.launch { runCatching { active.close() } }
        mutableState.update {
            it.copy(
                connectedDevice = null,
                connectionState = ConnectionState.Disconnected,
                services = emptyList(),
                notifying = emptySet(),
            )
        }
    }

    fun read(characteristic: BleCharacteristic) {
        val active = session ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            runCatching { active.read(characteristic) }.onSuccess { bytes ->
                mutableState.update { it.copy(values = it.values + (key(characteristic) to bytes)) }
                repository.log(LogCategory.GATT, "Read ${bytes.size} bytes", mutableState.value.connectedDevice?.id, characteristic.uuid.toString(), bytes)
            }.onFailure(::reportFailure)
            mutableState.update { it.copy(busy = false) }
        }
    }

    fun write(characteristic: BleCharacteristic, hex: String, withResponse: Boolean) {
        val bytes = parseHex(hex) ?: run {
            mutableState.update { it.copy(error = "Enter valid HEX bytes, for example: 01 A0 FF") }
            return
        }
        val active = session ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            runCatching { active.write(characteristic, bytes, withResponse) }.onSuccess {
                repository.log(LogCategory.GATT, "Wrote ${bytes.size} bytes", mutableState.value.connectedDevice?.id, characteristic.uuid.toString(), bytes)
            }.onFailure(::reportFailure)
            mutableState.update { it.copy(busy = false) }
        }
    }

    fun toggleNotifications(characteristic: BleCharacteristic) {
        val characteristicKey = key(characteristic)
        notificationJobs.remove(characteristicKey)?.let { job ->
            job.cancel()
            mutableState.update { it.copy(notifying = it.notifying - characteristicKey) }
            repository.log(LogCategory.NOTIFICATION, "Notifications disabled", mutableState.value.connectedDevice?.id, characteristic.uuid.toString())
            return
        }
        val active = session ?: return
        mutableState.update { it.copy(notifying = it.notifying + characteristicKey, error = null) }
        notificationJobs[characteristicKey] = viewModelScope.launch {
            active.notifications(characteristic).catch { error ->
                mutableState.update { it.copy(notifying = it.notifying - characteristicKey, error = error.message) }
                repository.log(LogCategory.ERROR, "Notification subscription failed: ${error.message}", mutableState.value.connectedDevice?.id, characteristic.uuid.toString())
            }.collect { bytes ->
                mutableState.update { it.copy(values = it.values + (characteristicKey to bytes)) }
                repository.log(LogCategory.NOTIFICATION, "Received ${bytes.size} bytes", mutableState.value.connectedDevice?.id, characteristic.uuid.toString(), bytes)
            }
        }
    }

    fun clearLogs() = repository.clearLogs()

    fun clearError() = mutableState.update { it.copy(error = null) }

    private fun reportFailure(error: Throwable) {
        mutableState.update { it.copy(error = error.message ?: "BLE operation failed") }
        repository.log(LogCategory.ERROR, "GATT operation failed: ${error.message}", mutableState.value.connectedDevice?.id)
    }

    private fun key(characteristic: BleCharacteristic) = "${characteristic.serviceUuid}/${characteristic.uuid}"

    private fun parseHex(input: String): ByteArray? {
        val tokens = input.trim().split(Regex("[\\s,:-]+")).filter(String::isNotBlank)
        if (tokens.isEmpty() || tokens.any { it.length !in 1..2 || it.toIntOrNull(16) == null }) return null
        return tokens.map { it.toInt(16).toByte() }.toByteArray()
    }
}

private fun DeviceLifecycleState.toConnectionState(): ConnectionState = when (this) {
    DeviceLifecycleState.Created,
    DeviceLifecycleState.Discovered,
    DeviceLifecycleState.Connecting,
    -> ConnectionState.Connecting
    DeviceLifecycleState.Connected -> ConnectionState.Connected
    DeviceLifecycleState.Initializing -> ConnectionState.DiscoveringServices
    DeviceLifecycleState.Ready,
    DeviceLifecycleState.RefreshingState,
    -> ConnectionState.Ready
    DeviceLifecycleState.Disconnecting -> ConnectionState.Disconnecting
    DeviceLifecycleState.Disconnected -> ConnectionState.Disconnected
    is DeviceLifecycleState.Error -> ConnectionState.Error(
        com.Fusion.Btremix.core.bluetooth.api.BleError.Unknown(error.message, error.cause),
    )
}

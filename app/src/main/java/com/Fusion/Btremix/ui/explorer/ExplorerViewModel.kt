package com.Fusion.Btremix.ui.explorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.Fusion.Btremix.BtRemixApplication
import com.Fusion.Btremix.core.bluetooth.BleRepository
import com.Fusion.Btremix.core.bluetooth.android.AndroidBleManager
import com.Fusion.Btremix.core.classic.android.AndroidRfcommManager
import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.core.bluetooth.api.BleService
import com.Fusion.Btremix.core.classic.api.ClassicDevice
import com.Fusion.Btremix.core.bluetooth.api.ConnectionState
import com.Fusion.Btremix.core.classic.api.RfcommManager
import com.Fusion.Btremix.core.logging.InMemoryLogger
import com.Fusion.Btremix.core.logging.LogCategory
import com.Fusion.Btremix.core.logging.LogEntry
import com.Fusion.Btremix.core.permissions.AndroidBluetoothPermissionManager
import com.Fusion.Btremix.core.permissions.PermissionStatus
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.TransportType
import com.Fusion.Btremix.definition.matcher.DefinitionMatcher
import com.Fusion.Btremix.definition.packages.DevicePackage
import com.Fusion.Btremix.definition.packages.DevicePackageManager
import com.Fusion.Btremix.definition.session.DefinitionSessionFactory
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.DeviceEvent
import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.device.runtime.DeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceSession
import com.Fusion.Btremix.device.runtime.ProtocolSession
import com.Fusion.Btremix.device.runtime.StateEntry
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a scanned device was recognised, shown next to the device in the list. */
data class DeviceDefinitionMatch(
    val packageId: String,
    val displayName: String,
    val version: String,
    val priority: Int,
    val rule: String,
    val matchedBy: String = MATCH_SCAN,
) {
    companion object {
        const val MATCH_SCAN: String = "scan"
        const val MATCH_GATT: String = "gatt"
        const val MATCH_CLASSIC: String = "classic"
    }
}

/** Which pane of a connected device is visible. */
enum class ExplorerView { DEVICE, RAW_GATT, MONITOR }

/** Structured action failure rendered on the definition device page. */
data class DefinitionActionError(val code: String, val message: String)

data class ExplorerUiState(
    val devices: List<BleScanResult> = emptyList(),
    val matches: Map<String, DeviceDefinitionMatch> = emptyMap(),
    val scanning: Boolean = false,
    val connectedDevice: BleDevice? = null,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val services: List<BleService> = emptyList(),
    val values: Map<String, ByteArray> = emptyMap(),
    val notifying: Set<String> = emptySet(),
    val connectedDefinition: LoadedDeviceDefinition? = null,
    val definitionState: Map<String, StateEntry> = emptyMap(),
    val actionError: DefinitionActionError? = null,
    val classicDevices: List<ClassicDevice> = emptyList(),
    /** False for byte-stream (SPP) sessions, which expose no GATT characteristics to explore. */
    val supportsRawGatt: Boolean = true,
    val view: ExplorerView = ExplorerView.DEVICE,
    val monitor: List<MonitorEntry> = emptyList(),
    val monitorQuery: String = "",
    val logs: List<LogEntry> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val permissionGranted: Boolean = false,
)

class ExplorerViewModel(application: Application) : AndroidViewModel(application) {
    private val logger = InMemoryLogger()
    private val repository = BleRepository(AndroidBleManager(application), logger)
    private val rfcomm: RfcommManager = AndroidRfcommManager(application)
    private val permissionManager = AndroidBluetoothPermissionManager()
    private val deviceRuntime: DeviceRuntime = DefaultDeviceRuntime()
    private val sessionFactory = DefinitionSessionFactory(deviceRuntime)
    private val packages: DevicePackageManager = (application as BtRemixApplication).packages
    private val mutableState = MutableStateFlow(ExplorerUiState())
    val state = mutableState.asStateFlow()
    private var session: ProtocolSession? = null
    private var gattSession: DeviceSession? = null
    private var scanJob: Job? = null
    private var connectionStateJob: Job? = null
    private var definitionStateJob: Job? = null
    private var sessionEventsJob: Job? = null
    private val notificationJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            repository.logs.collect { logs -> mutableState.update { it.copy(logs = logs) } }
        }
    }

    fun updatePermissionStatus(isGranted: (String) -> Boolean) {
        val granted = permissionManager.status(android.os.Build.VERSION.SDK_INT, isGranted) == PermissionStatus.Granted
        mutableState.update { it.copy(permissionGranted = granted, error = if (granted) null else it.error) }
        if (granted) refreshClassicDevices()
    }

    /**
     * Lists the bonded classic devices that a loaded classic (SPP) definition can actually drive.
     *
     * The Explorer is a BLE tool first, so a plain dump of every paired headset/keyboard would be
     * noise. Only peers whose name/address matches an rfcomm definition are surfaced; everything else
     * stays out of the main screen until such a definition is installed.
     */
    fun refreshClassicDevices() {
        viewModelScope.launch {
            val bonded = runCatching { rfcomm.bondedDevices() }.getOrDefault(emptyList())
            val matches = mutableMapOf<String, DeviceDefinitionMatch>()
            val usable = bonded.filter { device ->
                val scan = BleScanResult(BleDevice(device.address, device.name, device.address), rssi = 0)
                val matched = packages.registry.match(scan)?.takeIf {
                    it.devicePackage.definition.protocol.transport?.type == TransportType.RFCOMM
                } ?: return@filter false
                matches[device.address] = matched.toUiMatch(DeviceDefinitionMatch.MATCH_CLASSIC)
                true
            }
            mutableState.update { it.copy(classicDevices = usable, matches = it.matches + matches) }
        }
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
        mutableState.update { it.copy(scanning = true, devices = emptyList(), matches = emptyMap(), error = null) }
        scanJob = viewModelScope.launch {
            repository.scan().catch { error ->
                mutableState.update { it.copy(scanning = false, error = error.message ?: "BLE scan failed") }
                repository.log(LogCategory.ERROR, "Scan failed: ${error.message}")
            }.collect { result ->
                val match = matchFor(result)
                mutableState.update { current ->
                    val devices = (current.devices.filterNot { it.device.id == result.device.id } + result)
                        .sortedByDescending { it.rssi }
                    val matches = if (match != null) current.matches + (result.device.id to match)
                    else current.matches - result.device.id
                    current.copy(devices = devices, matches = matches)
                }
                repository.log(
                    LogCategory.SCAN,
                    "${result.device.name ?: result.device.address}, RSSI ${result.rssi} dBm" +
                        (match?.let { " · matched ${it.packageId}" } ?: ""),
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
                it.copy(
                    connectedDevice = device,
                    connectionState = ConnectionState.Connecting,
                    services = emptyList(),
                    error = null,
                    supportsRawGatt = true,
                )
            }
            val scannedMatch = mutableState.value.matches[device.id]
            val scannedPackage: DevicePackage? = scannedMatch?.let { packages.registry.find(it.packageId) }
                ?: packages.registry.match(BleScanResult(device, rssi = 0))?.devicePackage
            runCatching {
                val definition = scannedPackage?.definition
                if (definition?.protocol?.transport?.type == TransportType.RFCOMM) {
                    connectStream(device, definition)
                } else {
                    connectGatt(device, scannedPackage)
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(connectionState = ConnectionState.Error(com.Fusion.Btremix.core.bluetooth.api.BleError.Unknown(error.message ?: "Connection failed", error)), error = error.message ?: "Connection failed")
                }
                repository.log(LogCategory.ERROR, "Connection failed: ${error.message}", device.id)
            }
        }
    }

    /** Connects a classic Bluetooth (SPP) device using the definition's `protocol.transport`. */
    private suspend fun connectStream(device: BleDevice, definition: LoadedDeviceDefinition) {
        val transport = requireNotNull(definition.protocol.transport) {
            "Definition '${definition.id}' has no protocol.transport"
        }
        val connection = rfcomm.connect(device.address, UUID.fromString(transport.service))
        val active = sessionFactory.openStream(device, connection, definition)
        session = active
        gattSession = null
        val match = mutableState.value.matches[device.id]
            ?: packages.registry.match(BleScanResult(device, rssi = 0))?.toUiMatch(DeviceDefinitionMatch.MATCH_SCAN)
        if (match != null) mutableState.update { it.copy(matches = it.matches + (device.id to match)) }
        mutableState.update { it.copy(supportsRawGatt = false, view = ExplorerView.DEVICE) }
        wireSession(active, definition)
        repository.log(
            LogCategory.GATT,
            "Opened SPP session to ${device.address}" +
                " · definition ${definition.id} v${definition.manifest.version}",
            device.id,
        )
    }

    private suspend fun connectGatt(device: BleDevice, scannedPackage: DevicePackage?) {
        val connection = repository.connect(device)
        val scannedDefinition = scannedPackage?.definition
        val opened = if (scannedDefinition != null) {
            sessionFactory.open(device, connection, scannedDefinition) to scannedDefinition
        } else {
            // Only the advertised name/address was inspected so far; a definition that needs
            // service discovery gets its chance before the session is handed to the UI.
            val plain = deviceRuntime.open(device, connection)
            val gattMatch = packages.registry.match(plain.services.value)
            val attached = gattMatch?.let { sessionFactory.attach(plain, it.devicePackage.definition) } ?: plain
            attached to gattMatch?.devicePackage?.definition
        }
        val active = opened.first
        val definition = opened.second
        session = active
        gattSession = active
        if (definition != null) {
            val match = mutableState.value.matches[device.id]?.copy(matchedBy = DeviceDefinitionMatch.MATCH_SCAN)
                ?: packages.registry.match(active.services.value)?.let { it.toUiMatch(DeviceDefinitionMatch.MATCH_GATT) }
            if (match != null) mutableState.update { it.copy(matches = it.matches + (device.id to match)) }
        }
        wireSession(active, definition)
        val services = active.services.value
        repository.log(
            LogCategory.GATT,
            "Discovered ${services.size} services" +
                (definition?.let { " · definition ${it.id} v${it.manifest.version}" } ?: ""),
            device.id,
        )
    }

    /** Shared session wiring for both transports: lifecycle, events, definition state and actions. */
    private suspend fun wireSession(active: ProtocolSession, definition: LoadedDeviceDefinition?) {
        connectionStateJob?.cancel()
        connectionStateJob = viewModelScope.launch {
            active.lifecycle.collect { lifecycle ->
                mutableState.update {
                    it.copy(
                        connectionState = lifecycle.toConnectionState(),
                        services = (active as? DeviceSession)?.services?.value ?: emptyList(),
                    )
                }
            }
        }
        sessionEventsJob?.cancel()
        sessionEventsJob = viewModelScope.launch {
            active.events.collect { event -> recordMonitorEvent(event) }
        }
        if (definition != null) {
            mutableState.update { it.copy(connectedDefinition = definition, definitionState = active.state.entries.value) }
            definitionStateJob?.cancel()
            definitionStateJob = viewModelScope.launch {
                active.state.entries.collect { entries ->
                    mutableState.update { it.copy(definitionState = entries) }
                }
            }
        }
        mutableState.update { it.copy(connectionState = ConnectionState.Ready) }
    }

    /**
     * Runs one action from the definition device page. Failures stay on the page instead of only
     * reaching the log, so a device that silently ignores an action is visible to the user.
     */
    fun executeAction(action: DeviceAction) {
        val active = session ?: return
        viewModelScope.launch {
            when (val result = active.execute(action)) {
                is ActionResult.Success -> mutableState.update { it.copy(actionError = null) }
                is ActionResult.Failure -> {
                    mutableState.update {
                        it.copy(actionError = DefinitionActionError(result.error.code, result.error.message))
                    }
                    repository.log(
                        LogCategory.ERROR,
                        "Action ${action.id} failed: ${result.error.message}",
                        mutableState.value.connectedDevice?.id,
                    )
                }
            }
        }
    }

    fun clearActionError() = mutableState.update { it.copy(actionError = null) }

    fun setView(view: ExplorerView) = mutableState.update { it.copy(view = view) }

    fun setMonitorQuery(query: String) = mutableState.update { it.copy(monitorQuery = query) }

    fun clearMonitor() = mutableState.update { it.copy(monitor = emptyList()) }

    /** Records one session event into the bounded packet monitor. */
    private fun recordMonitorEvent(event: DeviceEvent) {
        val entry = when (event) {
            is DeviceEvent.NotificationReceived -> MonitorEntry(
                timestamp = event.timestamp,
                direction = MonitorDirection.NOTIFY,
                serviceUuid = event.characteristic.serviceUuid,
                characteristicUuid = event.characteristic.uuid,
                payload = event.data.clone(),
            )
            is DeviceEvent.GattRead -> MonitorEntry(
                timestamp = event.timestamp,
                direction = MonitorDirection.READ,
                serviceUuid = event.characteristic.serviceUuid,
                characteristicUuid = event.characteristic.uuid,
                payload = event.data.clone(),
            )
            is DeviceEvent.GattWritten -> MonitorEntry(
                timestamp = event.timestamp,
                direction = MonitorDirection.WRITE,
                serviceUuid = event.characteristic.serviceUuid,
                characteristicUuid = event.characteristic.uuid,
                payload = event.data.clone(),
                detail = if (event.withResponse) "with response" else "without response",
            )
            else -> null
        }
        if (entry == null) return
        mutableState.update { current ->
            current.copy(monitor = (current.monitor + entry).takeLast(MONITOR_LIMIT))
        }
    }

    fun disconnect() {
        notificationJobs.values.forEach(Job::cancel)
        notificationJobs.clear()
        connectionStateJob?.cancel()
        definitionStateJob?.cancel()
        definitionStateJob = null
        sessionEventsJob?.cancel()
        sessionEventsJob = null
        val active = session
        session = null
        gattSession = null
        if (active != null) viewModelScope.launch { runCatching { active.close() } }
        mutableState.update {
            it.copy(
                connectedDevice = null,
                connectionState = ConnectionState.Disconnected,
                services = emptyList(),
                notifying = emptySet(),
                connectedDefinition = null,
                definitionState = emptyMap(),
                actionError = null,
                supportsRawGatt = true,
                view = ExplorerView.DEVICE,
                monitor = emptyList(),
                monitorQuery = "",
            )
        }
    }

    fun read(characteristic: BleCharacteristic) {
        val active = gattSession ?: return reportNoGatt()
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
        val active = gattSession ?: return reportNoGatt()
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
        val active = gattSession ?: return reportNoGatt()
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

    private fun reportNoGatt() {
        mutableState.update { it.copy(error = "This session has no GATT characteristics") }
    }

    /**
     * Scan-time matching. Connect-time (GATT service/characteristic) matching is deliberately left
     * for a later milestone; when added it should extend this function rather than the scan loop.
     */
    private fun matchFor(scan: BleScanResult): DeviceDefinitionMatch? = packages.registry.match(scan)?.let { match ->
        match.toUiMatch(DeviceDefinitionMatch.MATCH_SCAN)
    }

    private fun com.Fusion.Btremix.definition.packages.DevicePackageMatch.toUiMatch(matchedBy: String) = DeviceDefinitionMatch(
        packageId = devicePackage.packageId,
        displayName = devicePackage.displayName,
        version = devicePackage.version,
        priority = priority,
        rule = DefinitionMatcher.label(rule),
        matchedBy = matchedBy,
    )

    private fun key(characteristic: BleCharacteristic) = "${characteristic.serviceUuid}/${characteristic.uuid}"

    private fun parseHex(input: String): ByteArray? {
        val tokens = input.trim().split(Regex("[\\s,:-]+")).filter(String::isNotBlank)
        if (tokens.isEmpty() || tokens.any { it.length !in 1..2 || it.toIntOrNull(16) == null }) return null
        return tokens.map { it.toInt(16).toByte() }.toByteArray()
    }

    private companion object {
        const val MONITOR_LIMIT = 200
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

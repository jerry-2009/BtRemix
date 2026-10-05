package com.fusion.melodyLinkNeo.ui.explorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fusion.melodyLinkNeo.BtRemixApplication
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleCharacteristic
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleScanResult
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleService
import com.fusion.melodyLinkNeo.core.classic.api.ClassicDevice
import com.fusion.melodyLinkNeo.core.bluetooth.api.ConnectionState
import com.fusion.melodyLinkNeo.core.logging.LogCategory
import com.fusion.melodyLinkNeo.core.logging.LogEntry
import com.fusion.melodyLinkNeo.core.permissions.AndroidBluetoothPermissionManager
import com.fusion.melodyLinkNeo.core.permissions.PermissionStatus
import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.definition.api.TransportType
import com.fusion.melodyLinkNeo.definition.matcher.DefinitionMatcher
import com.fusion.melodyLinkNeo.definition.packages.DevicePackage
import com.fusion.melodyLinkNeo.definition.packages.DevicePackageManager
import com.fusion.melodyLinkNeo.device.runtime.ActionResult
import com.fusion.melodyLinkNeo.device.runtime.DeviceAction
import com.fusion.melodyLinkNeo.device.runtime.DeviceEvent
import com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState
import com.fusion.melodyLinkNeo.device.runtime.DeviceSession
import com.fusion.melodyLinkNeo.device.runtime.ProtocolSession
import com.fusion.melodyLinkNeo.device.runtime.StateEntry
import com.fusion.melodyLinkNeo.device.session.SessionRegistry
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
    /**
     * Session-creation material is process-scoped (MELODY_BRIDGE_SPEC §11.2/§12 M2a): the Explorer
     * borrows the shared repository, RFCOMM backend, runtime and definition factory so the Compose
     * page and the Melody bridge work on one [SessionRegistry] session per MAC.
     */
    private val app = application as BtRemixApplication
    private val logger = app.logger
    private val repository = app.bleRepository
    private val rfcomm = app.rfcomm
    private val deviceRuntime = app.deviceRuntime
    private val sessionFactory = app.sessionFactory
    private val sessions: SessionRegistry = app.sessions
    private val permissionManager = AndroidBluetoothPermissionManager()
    private val packages: DevicePackageManager = app.packages
    private val mutableState = MutableStateFlow(ExplorerUiState())
    val state = mutableState.asStateFlow()
    private var session: ProtocolSession? = null
    /** Normalised MAC of the session this ViewModel currently holds a reference to, if any. */
    private var heldMac: String? = null
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
            detachHeldSession()
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
            try {
                val definition = scannedPackage?.definition
                val key = SessionRegistry.normalize(device.id)
                val active = sessions.acquire(key) { openSession(device, definition) }
                session = active
                heldMac = key
                gattSession = active as? DeviceSession
                val services = (active as? DeviceSession)?.services?.value ?: emptyList()
                val matchedDefinition = definition
                    ?: (active as? DeviceSession)?.let {
                        packages.registry.match(it.services.value)?.devicePackage?.definition
                    }
                // Reuse the match the list already showed when there is one; otherwise fall back to
                // the GATT match (for BLE) or the advertised name (for classic peers).
                val match = if (active is DeviceSession) {
                    mutableState.value.matches[device.id]?.copy(matchedBy = DeviceDefinitionMatch.MATCH_SCAN)
                        ?: packages.registry.match(services)?.let { it.toUiMatch(DeviceDefinitionMatch.MATCH_GATT) }
                } else {
                    mutableState.value.matches[device.id]
                        ?: packages.registry.match(BleScanResult(device, rssi = 0))?.toUiMatch(DeviceDefinitionMatch.MATCH_SCAN)
                }
                if (match != null) mutableState.update { it.copy(matches = it.matches + (device.id to match)) }
                mutableState.update { it.copy(supportsRawGatt = active is DeviceSession, view = ExplorerView.DEVICE) }
                wireSession(active, matchedDefinition)
                repository.log(
                    LogCategory.GATT,
                    if (active is DeviceSession) {
                        "Discovered ${services.size} services" +
                            (matchedDefinition?.let { " · definition ${it.id} v${it.manifest.version}" } ?: "")
                    } else {
                        "Opened SPP session to ${device.address}" +
                            (matchedDefinition?.let { " · definition ${it.id} v${it.manifest.version}" } ?: "")
                    },
                    device.id,
                )
            } catch (cancelled: CancellationException) {
                detachHeldSession()
                throw cancelled
            } catch (error: Throwable) {
                detachHeldSession()
                mutableState.update {
                    it.copy(connectionState = ConnectionState.Error(com.fusion.melodyLinkNeo.core.bluetooth.api.BleError.Unknown(error.message ?: "Connection failed", error)), error = error.message ?: "Connection failed")
                }
                repository.log(LogCategory.ERROR, "Connection failed: ${error.message}", device.id)
            }
        }
    }

    /**
     * Opens a fresh session for [device]. Only [SessionRegistry.acquire] calls this, and only when
     * no live session exists for the MAC; UI code must never open a connection on its own.
     */
    private suspend fun openSession(device: BleDevice, definition: LoadedDeviceDefinition?): ProtocolSession {
        if (definition != null && definition.protocol.transport?.type == TransportType.RFCOMM) {
            val transport = requireNotNull(definition.protocol.transport) {
                "Definition '${definition.id}' has no protocol.transport"
            }
            val connection = rfcomm.connect(device.address, UUID.fromString(transport.service))
            return sessionFactory.openStream(device, connection, definition)
        }
        val connection = repository.connect(device)
        if (definition != null) return sessionFactory.open(device, connection, definition)
        // Only the advertised name/address was inspected so far; a definition that needs service
        // discovery gets its chance before the session is handed to the UI.
        val plain = deviceRuntime.open(device, connection)
        val gattMatch = packages.registry.match(plain.services.value)
        return gattMatch?.let { sessionFactory.attach(plain, it.devicePackage.definition) } ?: plain
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

    /**
     * Releases this ViewModel's reference to the active session.
     *
     * MELODY_BRIDGE_SPEC D6: the front-end never closes a session it does not exclusively own. The
     * registry closes the connection only once the last holder releases it, so a session shared with
     * the Melody bridge (M2b) survives the Compose page disconnecting.
     */
    fun disconnect() {
        viewModelScope.launch { detachHeldSession() }
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

    /**
     * Cancels the collectors wired to the active session and releases the registry reference held by
     * this ViewModel. Idempotent: at most one reference is released per held session.
     */
    private suspend fun detachHeldSession() {
        cancelSessionJobs()
        val mac = heldMac
        heldMac = null
        session = null
        gattSession = null
        if (mac != null) sessions.release(mac)
    }

    private fun cancelSessionJobs() {
        notificationJobs.values.forEach(Job::cancel)
        notificationJobs.clear()
        connectionStateJob?.cancel()
        connectionStateJob = null
        definitionStateJob?.cancel()
        definitionStateJob = null
        sessionEventsJob?.cancel()
        sessionEventsJob = null
    }

    /**
     * Releases the last held session when the ViewModel goes away.
     *
     * `viewModelScope` is already cancelled by the time this runs, so the reference is handed to the
     * process-scoped registry (the missing cleanup that used to leak a connected session).
     */
    override fun onCleared() {
        super.onCleared()
        cancelSessionJobs()
        val mac = heldMac ?: return
        heldMac = null
        session = null
        gattSession = null
        sessions.releaseAsync(mac)
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

    private fun com.fusion.melodyLinkNeo.definition.packages.DevicePackageMatch.toUiMatch(matchedBy: String) = DeviceDefinitionMatch(
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
        com.fusion.melodyLinkNeo.core.bluetooth.api.BleError.Unknown(error.message, error.cause),
    )
}

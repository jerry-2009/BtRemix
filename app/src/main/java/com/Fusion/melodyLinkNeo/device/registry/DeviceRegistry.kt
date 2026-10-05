package com.fusion.melodyLinkNeo.device.registry

import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleScanResult
import com.fusion.melodyLinkNeo.core.classic.api.ClassicDevice
import com.fusion.melodyLinkNeo.definition.api.DeviceMatchInput
import com.fusion.melodyLinkNeo.definition.matcher.DefinitionMatcher
import com.fusion.melodyLinkNeo.definition.packages.DevicePackage
import com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import com.fusion.melodyLinkNeo.device.session.SessionRegistry
import com.fusion.melodyLinkNeo.device.session.SessionSnapshot
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted

/** Where a known device instance came from. */
enum class DeviceDiscoveryKind { BLE_SCAN, BONDED_CLASSIC, HOOK }

/** One discovered transport-level device, before package matching. */
data class DiscoveredDevice(
    val mac: String,
    val name: String? = null,
    val kind: DeviceDiscoveryKind = DeviceDiscoveryKind.BONDED_CLASSIC,
    val rssi: Int? = null,
    val bonded: Boolean = true,
    val serviceUuids: List<UUID> = emptyList(),
) {
    /** Normalised MAC, used as the registry key. */
    val key: String get() = SessionRegistry.normalize(mac)
}

/**
 * Uniform abstraction over "what devices exist right now" (DEVICE_CENTER_UI_PLAN §3.2).
 *
 * Production uses bonded classic devices plus optional BLE scan results; tests (and later the
 * Melody hook) can supply their own fake without touching Bluetooth.
 */
interface DeviceDiscoverySource {
    val devices: StateFlow<List<DiscoveredDevice>>

    /** Re-reads the underlying transports. */
    fun refresh()
}

enum class DeviceConnectionState { CONNECTED, CONNECTING, DISCONNECTED, ERROR }

/** A device the UI can show: an enabled package matched with a known instance. */
data class DeviceEntry(
    val key: String,
    val mac: String,
    val name: String?,
    val packageId: String,
    val packageDisplayName: String,
    val version: String,
    val author: String?,
    val deviceType: String?,
    val capabilities: List<String>,
    val discovery: DeviceDiscoveryKind,
    val rssi: Int?,
    val state: DeviceConnectionState,
    val batteryPercent: Int?,
) {
    val connected: Boolean get() = state == DeviceConnectionState.CONNECTED
}

/**
 * `DevicePackage × discovered instance → DeviceEntry` (DEVICE_CENTER_UI_PLAN §3.2/§5.2).
 *
 * Only *enabled* packages participate, which is what makes disabling a package remove its devices
 * from the page. A previously seen device stays listed as "未连接" because bonded classic instances
 * are always discoverable - that is the difference between a device centre and a scanner.
 */
class DeviceRegistry(
    private val packages: StateFlow<List<DevicePackage>>,
    private val enabledPackageIds: StateFlow<Set<String>>,
    private val discovery: DeviceDiscoverySource,
    private val sessions: StateFlow<List<SessionSnapshot>>,
    scope: CoroutineScope,
    private val batteryOf: (String, SessionSnapshot) -> Int? = { _, _ -> null },
) {
    val devices: StateFlow<List<DeviceEntry>> = combine(
        packages,
        enabledPackageIds,
        discovery.devices,
        sessions,
    ) { packageList, enabled, discovered, snapshots ->
        val activePackages = packageList.filter { it.packageId in enabled }
        val byMac = snapshots.associateBy { SessionRegistry.normalize(it.mac) }
        discovered
            .mapNotNull { device -> match(activePackages, device)?.let { device to it } }
            .map { (device, matched) ->
                val snapshot = byMac[device.key]
                matched.toEntry(device, snapshot, snapshot?.let { batteryOf(device.key, it) })
            }
            .distinctBy { it.key }
            .sortedWith(compareByDescending<DeviceEntry> { it.connected }.thenBy { it.packageDisplayName })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun refresh() = discovery.refresh()

    private fun match(packages: List<DevicePackage>, device: DiscoveredDevice): DevicePackage? =
        packages.asSequence()
            .mapNotNull { pkg -> DefinitionMatcher.rank(pkg.definition, device.toMatchInput())?.let { pkg to it.priority } }
            .maxByOrNull { it.second }
            ?.first

    private fun DiscoveredDevice.toMatchInput(): DeviceMatchInput = when (kind) {
        DeviceDiscoveryKind.BLE_SCAN -> DeviceMatchInput.Advertisement(
            BleScanResult(
                device = BleDevice(mac, name, mac),
                rssi = rssi ?: 0,
                serviceUuids = serviceUuids,
            ),
        )
        else -> DeviceMatchInput.Classic(ClassicDevice(address = mac, name = name, bonded = bonded))
    }

    private fun DevicePackage.toEntry(
        device: DiscoveredDevice,
        snapshot: SessionSnapshot?,
        battery: Int?,
    ): DeviceEntry = DeviceEntry(
        key = device.key,
        mac = device.mac,
        name = device.name,
        packageId = packageId,
        packageDisplayName = displayName,
        version = version,
        author = definition.manifest.author,
        deviceType = definition.manifest.deviceType,
        capabilities = capabilities,
        discovery = device.kind,
        rssi = device.rssi,
        state = snapshot.toConnectionState(),
        batteryPercent = battery,
    )
}

/** Lifecycle → UI state. */
fun SessionSnapshot?.toConnectionState(): DeviceConnectionState = when (this?.lifecycle) {
    null -> DeviceConnectionState.DISCONNECTED
    DeviceLifecycleState.Ready,
    DeviceLifecycleState.RefreshingState,
    DeviceLifecycleState.Connected,
    -> DeviceConnectionState.CONNECTED
    DeviceLifecycleState.Created,
    DeviceLifecycleState.Discovered,
    DeviceLifecycleState.Connecting,
    DeviceLifecycleState.Initializing,
    DeviceLifecycleState.Disconnecting,
    -> DeviceConnectionState.CONNECTING
    DeviceLifecycleState.Disconnected -> DeviceConnectionState.DISCONNECTED
    is DeviceLifecycleState.Error -> DeviceConnectionState.ERROR
}

/** Best-effort battery extraction from a session snapshot; `null` when the definition has none. */
fun batteryPercentOf(snapshot: SessionSnapshot): Int? {
    val candidate = BATTERY_KEYS.firstNotNullOfOrNull { key -> snapshot.state[key]?.value }
    val number = when (candidate) {
        is StateValue.IntValue -> candidate.value
        is StateValue.LongValue -> candidate.value.toInt()
        is StateValue.FloatValue -> candidate.value.toInt()
        is StateValue.DoubleValue -> candidate.value.toInt()
        is StateValue.StringValue -> candidate.value.toIntOrNull()
        else -> null
    } ?: return null
    return number.takeIf { it in 0..100 }
}

private val BATTERY_KEYS = listOf(
    "battery",
    "batteryLevel",
    "battery_level",
    "batteryPercent",
    "battery_percent",
)

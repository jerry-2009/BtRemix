package com.fusion.melodyLinkNeo.device.registry

import com.fusion.melodyLinkNeo.core.bluetooth.BleRepository
import com.fusion.melodyLinkNeo.core.classic.api.RfcommManager
import com.fusion.melodyLinkNeo.device.session.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Production discovery: bonded classic devices are always listed (so a paired headset that is not
 * connected still appears), and a BLE scan can be layered on top while the user asks for it.
 *
 * Discovery deliberately knows nothing about device packages; matching happens in [DeviceRegistry].
 */
class AndroidDeviceDiscoverySource(
    private val rfcomm: RfcommManager,
    private val ble: BleRepository,
    private val scope: CoroutineScope,
    private val refreshIntervalMs: Long = 30_000L,
) : DeviceDiscoverySource {

    private val mutableDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val devices: StateFlow<List<DiscoveredDevice>> = mutableDevices.asStateFlow()

    private val bleResults = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    private var scanJob: Job? = null

    init {
        if (refreshIntervalMs > 0) {
            scope.launch {
                while (isActive) {
                    refreshClassic()
                    delay(refreshIntervalMs)
                }
            }
        }
        scope.launch {
            bleResults.collect { publish() }
        }
    }

    override fun refresh() {
        scope.launch { refreshClassic() }
    }

    /** Starts a BLE scan if one is not already running; results merge into [devices]. */
    fun startBleScan() {
        if (scanJob != null) return
        scanJob = scope.launch {
            ble.scan().catch { }.collect { result ->
                val device = DiscoveredDevice(
                    mac = result.device.address,
                    name = result.device.name,
                    kind = DeviceDiscoveryKind.BLE_SCAN,
                    rssi = result.rssi,
                    serviceUuids = result.serviceUuids,
                )
                bleResults.update { it + (device.key to device) }
            }
        }
    }

    fun stopBleScan() {
        val job = scanJob ?: return
        scanJob = null
        job.cancel()
        scope.launch { runCatching { ble.stopScan() } }
    }

    private suspend fun refreshClassic() {
        val bonded = runCatching { rfcomm.bondedDevices() }.getOrDefault(emptyList())
        val classic = bonded.map { device ->
            DiscoveredDevice(
                mac = device.address,
                name = device.name,
                kind = DeviceDiscoveryKind.BONDED_CLASSIC,
                bonded = device.bonded,
            )
        }
        mutableDevices.value = merge(classic, bleResults.value.values.toList())
    }

    private fun publish() {
        mutableDevices.update { current ->
            merge(current.filter { it.kind == DeviceDiscoveryKind.BONDED_CLASSIC }, bleResults.value.values.toList())
        }
    }

    private fun merge(classic: List<DiscoveredDevice>, scanned: List<DiscoveredDevice>): List<DiscoveredDevice> {
        val classicKeys = classic.mapTo(mutableSetOf()) { it.key }
        return (classic + scanned)
            .filterNot { it.kind == DeviceDiscoveryKind.BLE_SCAN && it.key in classicKeys }
            .distinctBy { it.key }
            .sortedBy { it.key }
    }
}

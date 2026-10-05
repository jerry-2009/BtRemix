package com.fusion.melodyLinkNeo.ui.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleCharacteristic
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleCharacteristicProperty
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleScanResult
import com.fusion.melodyLinkNeo.core.classic.api.ClassicDevice
import com.fusion.melodyLinkNeo.core.bluetooth.api.ConnectionState
import com.fusion.melodyLinkNeo.core.logging.LogEntry
import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.device.runtime.DeviceAction
import com.fusion.melodyLinkNeo.ui.renderer.DefinitionDevicePage
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Scan results with the Definition each device matched, if any.
 *
 * Stateless on purpose: the composable only renders [ExplorerUiState] and forwards the connect
 * intent, which keeps both the match badge and the unmatched fallback testable.
 */
@Composable
fun DeviceList(
    state: ExplorerUiState,
    onConnect: (BleDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One scrollable list for the whole screen. Anything emitted outside it (as the paired-device
    // section used to be) is laid out at fixed height and pushes the scan results off screen.
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        item(key = "nearby-header") {
            SectionHeader(
                title = if (state.scanning) "Nearby devices · scanning" else "Nearby devices",
                count = state.devices.size,
            )
        }
        if (state.devices.isEmpty()) {
            item(key = "nearby-empty") {
                Text("No devices found", Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(state.devices, key = { "scan:${it.device.id}" }) { result ->
                ScanResultRow(result, state.matches[result.device.id], onConnect)
            }
        }
        if (state.classicDevices.isNotEmpty()) {
            item(key = "classic-header") {
                SectionHeader(title = "Paired devices · classic Bluetooth", count = state.classicDevices.size)
            }
            items(state.classicDevices, key = { "classic:${it.address}" }) { pair ->
                PairedDeviceRow(pair, state.matches[pair.address], onConnect)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScanResultRow(
    result: BleScanResult,
    match: DeviceDefinitionMatch?,
    onConnect: (BleDevice) -> Unit,
) {
    Surface(modifier = Modifier.clickable { onConnect(result.device) }, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(result.device.name ?: "Unknown device", fontWeight = FontWeight.Medium)
                Text("${result.rssi} dBm", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(result.device.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (match != null) MatchBadge(match) else Text("Unrecognized device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (result.manufacturerData.isNotEmpty()) {
                Text("Manufacturer: ${manufacturerHex(result.manufacturerData)}", style = MaterialTheme.typography.bodySmall)
            }
            if (result.serviceUuids.isNotEmpty()) {
                Text("Services: ${result.serviceUuids.joinToString()}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
    Divider()
}

@Composable
private fun PairedDeviceRow(
    pair: ClassicDevice,
    match: DeviceDefinitionMatch?,
    onConnect: (BleDevice) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConnect(BleDevice(id = pair.address, name = pair.name, address = pair.address)) },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(pair.name ?: "Paired device", fontWeight = FontWeight.Medium)
            Text(pair.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (match != null) {
                MatchBadge(match)
            } else {
                Text(
                    "Paired · no classic (SPP) definition installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Divider()
}

@Composable
private fun MatchBadge(match: DeviceDefinitionMatch) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                "Matched ${match.displayName} v${match.version}" +
                    when (match.matchedBy) {
                        DeviceDefinitionMatch.MATCH_GATT -> " · via GATT"
                        DeviceDefinitionMatch.MATCH_CLASSIC -> " · via classic SPP"
                        else -> ""
                    },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "${match.packageId} · priority ${match.priority} · ${match.rule}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * Connected device view.
 *
 * A device with a matched Definition gets a [DefinitionDevicePage] driven by the session state;
 * the raw GATT tools stay reachable through the "Raw GATT" tab. Unmatched devices keep exactly the
 * pre-milestone behaviour.
 */
@Composable
fun ConnectionDetails(
    state: ExplorerUiState,
    onRead: (BleCharacteristic) -> Unit,
    onWrite: (BleCharacteristic, String, Boolean) -> Unit,
    onToggleNotifications: (BleCharacteristic) -> Unit,
    onAction: (DeviceAction) -> Unit,
    onSelectView: (ExplorerView) -> Unit,
    onClearActionError: () -> Unit,
    onMonitorQuery: (String) -> Unit = {},
    onClearMonitor: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val device = state.connectedDevice ?: return
    val definition = state.connectedDefinition
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(device.name ?: "Unknown device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        Text("${device.address} · ${state.connectionState.label()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (definition != null) {
            Text(
                "Definition ${definition.id} v${definition.manifest.version}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ViewTab("Device", state.view == ExplorerView.DEVICE, Modifier.weight(1f)) { onSelectView(ExplorerView.DEVICE) }
                if (state.supportsRawGatt) {
                    ViewTab("Raw GATT", state.view == ExplorerView.RAW_GATT, Modifier.weight(1f)) { onSelectView(ExplorerView.RAW_GATT) }
                }
                ViewTab("Monitor", state.view == ExplorerView.MONITOR, Modifier.weight(1f)) { onSelectView(ExplorerView.MONITOR) }
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }
        when {
            definition != null && state.view == ExplorerView.MONITOR -> MonitorView(
                definition = definition,
                state = state,
                onQuery = onMonitorQuery,
                onClear = onClearMonitor,
            )
            // A byte-stream session has no characteristics, so "Device" is the only GATT-free view.
            definition != null && (state.view == ExplorerView.DEVICE || !state.supportsRawGatt) -> {
                state.actionError?.let { error ->
                    ActionErrorBanner(error, onClearActionError)
                    Spacer(Modifier.height(8.dp))
                }
                DefinitionDevicePage(
                    definition = definition,
                    state = state.definitionState,
                    onAction = onAction,
                )
            }
            else -> RawGattView(state, onRead, onWrite, onToggleNotifications)
        }
    }
}

/**
 * Session-scoped capture of every GATT read, write and notification, decoded with the active
 * definition's protocol section when one is available.
 */
@Composable
private fun MonitorView(
    definition: LoadedDeviceDefinition,
    state: ExplorerUiState,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val debugger = remember(definition) { ProtocolDebugger(definition) }
    val filtered = state.monitor.filter { it.matches(state.monitorQuery) }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Packet monitor", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text("${filtered.size}/${state.monitor.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(
            enabled = state.monitor.isNotEmpty(),
            onClick = {
                clipboard.setText(AnnotatedString(state.monitor.joinToString("\n") { entry -> entry.describe(debugger) }))
            },
        ) { Text("Copy") }
        TextButton(enabled = state.monitor.isNotEmpty(), onClick = onClear) { Text("Clear") }
    }
    OutlinedTextField(
        value = state.monitorQuery,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Filter direction, UUID or hex") },
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    if (filtered.isEmpty()) {
        Text(
            if (state.monitor.isEmpty()) "No GATT traffic captured yet" else "No packets match the filter",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth().height(360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(filtered) { entry ->
            MonitorRow(entry, debugger)
            Divider()
        }
    }
}

@Composable
private fun MonitorRow(entry: MonitorEntry, debugger: ProtocolDebugger) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${entry.timestamp.atZone(ZoneId.systemDefault()).format(LOG_TIME)}  ${entry.direction.name}",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                entry.characteristicUuid.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(entry.payload.toHex(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        entry.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val decode = debugger.decode(entry.payload)
        if (decode.error != null) {
            Text("decode: ${decode.error}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        } else {
            Text(
                "cmd ${decode.command}${decode.sequence?.let { " · seq $it" } ?: ""} · ${decode.messageName}: " +
                    decode.fields.joinToString(", ") { "${it.name}=${it.value}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun MonitorEntry.describe(debugger: ProtocolDebugger): String {
    val decode = debugger.decode(payload)
    val decoded = if (decode.error != null) decode.error else "${decode.messageName}: " +
        decode.fields.joinToString(", ") { "${it.name}=${it.value}" }
    return "$timestamp ${direction.name} ${characteristicUuid} ${payload.toHex()} $decoded"
}

@Composable
private fun ViewTab(label: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onSelect),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionErrorBanner(error: DefinitionActionError, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${error.code}: ${error.message}",
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun RawGattView(
    state: ExplorerUiState,
    onRead: (BleCharacteristic) -> Unit,
    onWrite: (BleCharacteristic, String, Boolean) -> Unit,
    onToggleNotifications: (BleCharacteristic) -> Unit,
) {
    if (state.services.isEmpty()) {
        Text("${state.connectionState.label()}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.services.forEach { service ->
        Text(if (service.isPrimary) "Primary service" else "Secondary service", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(service.uuid.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        service.characteristics.forEach { characteristic ->
            CharacteristicRow(state, characteristic, onRead, onWrite, onToggleNotifications)
            Divider()
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun CharacteristicRow(
    state: ExplorerUiState,
    characteristic: BleCharacteristic,
    onRead: (BleCharacteristic) -> Unit,
    onWrite: (BleCharacteristic, String, Boolean) -> Unit,
    onToggleNotifications: (BleCharacteristic) -> Unit,
) {
    val key = "${characteristic.serviceUuid}/${characteristic.uuid}"
    val properties = characteristic.properties
    var hex by rememberSaveable(key) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(characteristic.uuid.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Text(properties.joinToString(" · ") { it.name }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val value = state.values[key]
        if (value != null) Text("Value: ${value.toHex()}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (BleCharacteristicProperty.READ in properties) OutlinedButton(onClick = { onRead(characteristic) }, enabled = !state.busy) { Text("Read") }
            if (BleCharacteristicProperty.NOTIFY in properties || BleCharacteristicProperty.INDICATE in properties) {
                OutlinedButton(onClick = { onToggleNotifications(characteristic) }) {
                    Text(if (key in state.notifying) "Unsubscribe" else "Subscribe")
                }
            }
        }
        if (BleCharacteristicProperty.WRITE in properties || BleCharacteristicProperty.WRITE_WITHOUT_RESPONSE in properties) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Write HEX") },
                    placeholder = { Text("01 A0 FF") },
                    singleLine = true,
                )
                if (BleCharacteristicProperty.WRITE in properties) {
                    OutlinedButton(onClick = { onWrite(characteristic, hex, true) }, enabled = !state.busy) { Text("Write") }
                }
                if (BleCharacteristicProperty.WRITE_WITHOUT_RESPONSE in properties) {
                    OutlinedButton(onClick = { onWrite(characteristic, hex, false) }, enabled = !state.busy) { Text("No response") }
                }
            }
        }
    }
}

@Composable
fun LogPanel(logs: List<LogEntry>, clearLogs: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Logs", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(logs.joinToString("\n") { entry ->
                "${entry.timestamp} ${entry.category} ${entry.message}" +
                    (entry.deviceId?.let { " device=$it" } ?: "") +
                    (entry.uuid?.let { " uuid=$it" } ?: "") +
                    (entry.data?.let { " data=${it.toHex()}" } ?: "")
            }))
        }, enabled = logs.isNotEmpty()) { Text("Copy") }
        TextButton(onClick = clearLogs) { Text("Clear") }
    }
    LazyColumn(Modifier.fillMaxWidth().height(150.dp)) {
        items(logs.takeLast(100).asReversed()) { entry ->
            Text(
                "${entry.timestamp.atZone(ZoneId.systemDefault()).format(LOG_TIME)}  ${entry.category.name}  ${entry.message}" +
                    (entry.uuid?.let { "  $it" } ?: "") +
                    (entry.data?.let { "  ${it.toHex()}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

private fun ConnectionState.label(): String = when (this) {
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Connected -> "Connected"
    ConnectionState.DiscoveringServices -> "Discovering services"
    ConnectionState.Ready -> "Ready"
    ConnectionState.Disconnecting -> "Disconnecting"
    is ConnectionState.Error -> "Error: ${error.message}"
}

private fun manufacturerHex(data: Map<Int, ByteArray>): String = data.entries.joinToString(" · ") { (id, value) ->
    "%04X: %s".format(id, value.toHex())
}

private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

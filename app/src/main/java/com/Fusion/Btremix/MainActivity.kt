package com.Fusion.Btremix

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristicProperty
import com.Fusion.Btremix.core.bluetooth.api.BleError
import com.Fusion.Btremix.core.bluetooth.api.ConnectionState
import com.Fusion.Btremix.core.logging.LogEntry
import com.Fusion.Btremix.ui.explorer.ExplorerUiState
import com.Fusion.Btremix.ui.explorer.ExplorerViewModel
import com.Fusion.Btremix.ui.packages.DefinitionPackagesScreen
import com.Fusion.Btremix.ui.packages.PackageToolsViewModel
import com.Fusion.Btremix.ui.theme.BtRemixTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val explorerViewModel by viewModels<ExplorerViewModel>()
    private val packagesViewModel by viewModels<PackageToolsViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BtRemixTheme { BtRemixApp(explorerViewModel, packagesViewModel) } }
    }
}

private enum class AppPage { Explorer, Packages }

@Composable
private fun BtRemixApp(explorerViewModel: ExplorerViewModel, packagesViewModel: PackageToolsViewModel) {
    var page by rememberSaveable { mutableStateOf(AppPage.Explorer) }
    val packageState by packagesViewModel.state.collectAsState()
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PageTab("Explorer", page == AppPage.Explorer, Modifier.weight(1f)) { page = AppPage.Explorer }
                PageTab("Packages", page == AppPage.Packages, Modifier.weight(1f)) { page = AppPage.Packages }
            }
            when (page) {
                AppPage.Explorer -> BleExplorer(explorerViewModel, Modifier.weight(1f))
                AppPage.Packages -> DefinitionPackagesScreen(
                    state = packageState,
                    onPackagePicked = packagesViewModel::install,
                    onReload = packagesViewModel::reload,
                    onDelete = packagesViewModel::delete,
                    onSelect = packagesViewModel::select,
                    onConfirmReplace = packagesViewModel::confirmReplace,
                    onCancelReplace = packagesViewModel::cancelReplace,
                    onDismissMessage = packagesViewModel::dismissMessage,
                    onClearErrors = packagesViewModel::clearErrors,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PageTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BleExplorer(viewModel: ExplorerViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.updatePermissionStatus { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.updatePermissionStatus { permission ->
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
    ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("BLE Explorer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Raw Bluetooth Low Energy tools", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.connectedDevice == null) {
                    Button(onClick = {
                        if (state.permissionGranted) viewModel.toggleScan()
                        else permissionLauncher.launch(viewModel.requiredPermissions())
                    }) { Text(if (state.scanning) "Stop scan" else "Scan") }
                } else {
                    OutlinedButton(onClick = viewModel::disconnect) { Text("Disconnect") }
                }
            }
            state.error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (!state.permissionGranted) {
                Text("Bluetooth permission is needed to find and connect to nearby devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { permissionLauncher.launch(viewModel.requiredPermissions()) }) { Text("Grant permission") }
            }
            if (state.connectedDevice == null) {
                DeviceList(state, viewModel, Modifier.weight(1f))
            } else {
                ConnectionDetails(state, viewModel, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Divider()
            LogPanel(state.logs, viewModel::clearLogs)
    }
}

@Composable
private fun DeviceList(state: ExplorerUiState, viewModel: ExplorerViewModel, modifier: Modifier) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (state.scanning) "Nearby devices · scanning" else "Nearby devices", style = MaterialTheme.typography.titleMedium)
        Text("${state.devices.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.devices.isEmpty()) {
        Text("No devices found", Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(state.devices, key = { it.device.id }) { result ->
                Surface(modifier = Modifier.clickable { viewModel.connect(result.device) }, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(result.device.name ?: "Unknown device", fontWeight = FontWeight.Medium)
                            Text("${result.rssi} dBm", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(result.device.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
        }
    }
}

@Composable
private fun ConnectionDetails(state: ExplorerUiState, viewModel: ExplorerViewModel, modifier: Modifier) {
    val device = state.connectedDevice ?: return
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(device.name ?: "Unknown device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        Text("${device.address} · ${state.connectionState.label()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (state.services.isEmpty()) {
            Text("${state.connectionState.label()}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.services.forEach { service ->
            Text(if (service.isPrimary) "Primary service" else "Secondary service", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(service.uuid.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            service.characteristics.forEach { characteristic ->
                CharacteristicRow(state, characteristic, viewModel)
                Divider()
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CharacteristicRow(state: ExplorerUiState, characteristic: BleCharacteristic, viewModel: ExplorerViewModel) {
    val key = "${characteristic.serviceUuid}/${characteristic.uuid}"
    val properties = characteristic.properties
    var hex by rememberSaveable(key) { androidx.compose.runtime.mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(characteristic.uuid.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Text(properties.joinToString(" · ") { it.name }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val value = state.values[key]
        if (value != null) Text("Value: ${value.toHex()}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (BleCharacteristicProperty.READ in properties) OutlinedButton(onClick = { viewModel.read(characteristic) }, enabled = !state.busy) { Text("Read") }
            if (BleCharacteristicProperty.NOTIFY in properties || BleCharacteristicProperty.INDICATE in properties) {
                OutlinedButton(onClick = { viewModel.toggleNotifications(characteristic) }) {
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
                    OutlinedButton(onClick = { viewModel.write(characteristic, hex, true) }, enabled = !state.busy) { Text("Write") }
                }
                if (BleCharacteristicProperty.WRITE_WITHOUT_RESPONSE in properties) {
                    OutlinedButton(onClick = { viewModel.write(characteristic, hex, false) }, enabled = !state.busy) { Text("No response") }
                }
            }
        }
    }
}

@Composable
private fun LogPanel(logs: List<LogEntry>, clearLogs: () -> Unit) {
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

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xff) }

private fun manufacturerHex(data: Map<Int, ByteArray>): String = data.entries.joinToString(" · ") { (id, value) ->
    "%04X: %s".format(id, value.toHex())
}

private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

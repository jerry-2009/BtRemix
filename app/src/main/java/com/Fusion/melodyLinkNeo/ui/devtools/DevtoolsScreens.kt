package com.fusion.melodyLinkNeo.ui.devtools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fusion.melodyLinkNeo.ui.explorer.ConnectionDetails
import com.fusion.melodyLinkNeo.ui.explorer.DeviceList
import com.fusion.melodyLinkNeo.ui.explorer.ExplorerViewModel
import com.fusion.melodyLinkNeo.ui.explorer.LogPanel
import com.fusion.melodyLinkNeo.ui.melody.MelodyDiagnosticsScreen
import com.fusion.melodyLinkNeo.ui.studio.DefinitionStudioScreen
import com.fusion.melodyLinkNeo.ui.studio.StudioViewModel

/**
 * Developer tools (D-UI-1): Explorer, Studio and Melody diagnostics, reached from
 * `Settings → 开发者` instead of the first-level tabs.
 *
 * These wrappers reuse the existing screens verbatim rather than rewriting them; only the entry
 * point changed.
 */
@Composable
fun ExplorerScreen(viewModel: ExplorerViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.updatePermissionStatus { permission ->
            ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.updatePermissionStatus { permission ->
                    ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("BLE Explorer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Raw Bluetooth Low Energy tools",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            Text(
                "Bluetooth permission is needed to find and connect to nearby devices.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { permissionLauncher.launch(viewModel.requiredPermissions()) }) { Text("Grant permission") }
        }
        if (state.connectedDevice == null) {
            DeviceList(state, onConnect = viewModel::connect, modifier = Modifier.weight(1f))
        } else {
            ConnectionDetails(
                state = state,
                onRead = viewModel::read,
                onWrite = viewModel::write,
                onToggleNotifications = viewModel::toggleNotifications,
                onAction = viewModel::executeAction,
                onSelectView = viewModel::setView,
                onClearActionError = viewModel::clearActionError,
                onMonitorQuery = viewModel::setMonitorQuery,
                onClearMonitor = viewModel::clearMonitor,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Divider()
        LogPanel(state.logs, viewModel::clearLogs)
    }
}

@Composable
fun StudioScreen(viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    DefinitionStudioScreen(
        state = state,
        onEditorChange = viewModel::updateEditor,
        onValidate = { viewModel.validate() },
        onSaveDraft = viewModel::saveDraft,
        onLoadDraft = viewModel::loadDraft,
        onDeleteDraft = viewModel::deleteDraft,
        onToggleSimulation = {
            if (state.simulator == null) viewModel.startSimulation() else viewModel.stopSimulation()
        },
        onAction = viewModel::runSimulatorAction,
        onSelectTarget = viewModel::selectNotifyTarget,
        onNotificationHex = viewModel::setNotificationHex,
        onInject = viewModel::injectNotification,
        onExport = viewModel::export,
        onDismissMessage = viewModel::dismissMessage,
        modifier = modifier,
    )
}

@Composable
fun MelodyDiagnosticsRoute(modifier: Modifier = Modifier) {
    MelodyDiagnosticsScreen(modifier)
}

package com.Fusion.Btremix

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.Fusion.Btremix.ui.explorer.ConnectionDetails
import com.Fusion.Btremix.ui.explorer.DeviceList
import com.Fusion.Btremix.ui.explorer.ExplorerViewModel
import com.Fusion.Btremix.ui.explorer.LogPanel
import com.Fusion.Btremix.ui.packages.DefinitionPackagesScreen
import com.Fusion.Btremix.ui.packages.PackageToolsViewModel
import com.Fusion.Btremix.ui.studio.DefinitionStudioScreen
import com.Fusion.Btremix.ui.studio.StudioViewModel
import com.Fusion.Btremix.ui.theme.BtRemixTheme

class MainActivity : ComponentActivity() {
    private val explorerViewModel by viewModels<ExplorerViewModel>()
    private val packagesViewModel by viewModels<PackageToolsViewModel>()
    private val studioViewModel by viewModels<StudioViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BtRemixTheme { BtRemixApp(explorerViewModel, packagesViewModel, studioViewModel) } }
    }
}

private enum class AppPage { Explorer, Packages, Studio }

@Composable
private fun BtRemixApp(
    explorerViewModel: ExplorerViewModel,
    packagesViewModel: PackageToolsViewModel,
    studioViewModel: StudioViewModel,
) {
    var page by rememberSaveable { mutableStateOf(AppPage.Explorer) }
    val packageState by packagesViewModel.state.collectAsState()
    val studioState by studioViewModel.state.collectAsState()
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PageTab("Explorer", page == AppPage.Explorer, Modifier.weight(1f)) { page = AppPage.Explorer }
                PageTab("Packages", page == AppPage.Packages, Modifier.weight(1f)) { page = AppPage.Packages }
                PageTab("Studio", page == AppPage.Studio, Modifier.weight(1f)) { page = AppPage.Studio }
            }
            when (page) {
                AppPage.Explorer -> BleExplorer(explorerViewModel, Modifier.weight(1f))
                AppPage.Packages -> DefinitionPackagesScreen(
                    state = packageState,
                    onPackagePicked = packagesViewModel::install,
                    onReload = packagesViewModel::reload,
                    onDelete = packagesViewModel::delete,
                    onSelect = packagesViewModel::select,
                    onPreview = packagesViewModel::preview,
                    onDismissPreview = packagesViewModel::dismissPreview,
                    onConfirmReplace = packagesViewModel::confirmReplace,
                    onCancelReplace = packagesViewModel::cancelReplace,
                    onDismissMessage = packagesViewModel::dismissMessage,
                    onClearErrors = packagesViewModel::clearErrors,
                    modifier = Modifier.weight(1f),
                )
                AppPage.Studio -> DefinitionStudioScreen(
                    state = studioState,
                    onEditorChange = studioViewModel::updateEditor,
                    onValidate = { studioViewModel.validate() },
                    onSaveDraft = studioViewModel::saveDraft,
                    onLoadDraft = studioViewModel::loadDraft,
                    onDeleteDraft = studioViewModel::deleteDraft,
                    onToggleSimulation = {
                        if (studioState.simulator == null) studioViewModel.startSimulation() else studioViewModel.stopSimulation()
                    },
                    onAction = studioViewModel::runSimulatorAction,
                    onSelectTarget = studioViewModel::selectNotifyTarget,
                    onNotificationHex = studioViewModel::setNotificationHex,
                    onInject = studioViewModel::injectNotification,
                    onExport = studioViewModel::export,
                    onDismissMessage = studioViewModel::dismissMessage,
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

package com.Fusion.Btremix

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
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
import com.Fusion.Btremix.ui.melody.MelodyDiagnosticsScreen
import com.Fusion.Btremix.ui.packages.DefinitionPackagesScreen
import com.Fusion.Btremix.ui.packages.PackageToolsViewModel
import com.Fusion.Btremix.ui.studio.DefinitionStudioScreen
import com.Fusion.Btremix.ui.studio.StudioViewModel
import com.Fusion.Btremix.ui.theme.BtRemixTheme
import com.Fusion.Btremix.melody.bridge.MelodyHostUpdateState
import com.Fusion.Btremix.melody.bridge.MelodyHostUpdateTracker

class MainActivity : ComponentActivity() {
    private val explorerViewModel by viewModels<ExplorerViewModel>()
    private val packagesViewModel by viewModels<PackageToolsViewModel>()
    private val studioViewModel by viewModels<StudioViewModel>()

    /** Set from the notification / explicit intent so a cold start lands on the Melody page. */
    private val requestedPage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedPage.value = intent?.getStringExtra(MelodyHostUpdateTracker.EXTRA_PAGE)
        setContent {
            BtRemixTheme {
                BtRemixApp(
                    explorerViewModel = explorerViewModel,
                    packagesViewModel = packagesViewModel,
                    studioViewModel = studioViewModel,
                    requestedPage = requestedPage.value,
                    onRequestedPageConsumed = { requestedPage.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedPage.value = intent.getStringExtra(MelodyHostUpdateTracker.EXTRA_PAGE)
    }
}

private enum class AppPage { Explorer, Melody, Packages, Studio }

@Composable
private fun BtRemixApp(
    explorerViewModel: ExplorerViewModel,
    packagesViewModel: PackageToolsViewModel,
    studioViewModel: StudioViewModel,
    requestedPage: String?,
    onRequestedPageConsumed: () -> Unit,
) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(AppPage.Explorer) }
    val packageState by packagesViewModel.state.collectAsState()
    val studioState by studioViewModel.state.collectAsState()
    val hostUpdate by MelodyHostUpdateTracker.state.collectAsState()
    LaunchedEffect(requestedPage) {
        if (requestedPage == MelodyHostUpdateTracker.PAGE_MELODY) {
            page = AppPage.Melody
            onRequestedPageConsumed()
        }
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HostUpdateBanner(
                state = hostUpdate,
                onOpen = {
                    page = AppPage.Melody
                    MelodyHostUpdateTracker.acknowledge(context.applicationContext)
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PageTab("Explorer", page == AppPage.Explorer, Modifier.weight(1f)) { page = AppPage.Explorer }
                PageTab("Melody", page == AppPage.Melody, Modifier.weight(1f)) { page = AppPage.Melody }
                PageTab("Packages", page == AppPage.Packages, Modifier.weight(1f)) { page = AppPage.Packages }
                PageTab("Studio", page == AppPage.Studio, Modifier.weight(1f)) { page = AppPage.Studio }
            }
            when (page) {
                AppPage.Explorer -> BleExplorer(explorerViewModel, Modifier.weight(1f))
                AppPage.Melody -> MelodyDiagnosticsScreen(Modifier.weight(1f))
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

/**
 * M6: slim, dismissible-looking banner shown on every page while a Melody update has not been
 * acknowledged. Opening it switches to the Melody page (which carries the full anchor report) and marks
 * the current install as seen, so the banner and the system notification go away together.
 */
@Composable
private fun HostUpdateBanner(state: MelodyHostUpdateState?, onOpen: () -> Unit) {
    if (state == null || !state.updateDetected) return
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.installId) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Melody 已更新" + (state.version?.let { "  $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    if (state.awaitingHost) {
                        "宿主启动后自动用 DexKit 重新定位锚点"
                    } else {
                        "已重新定位锚点 ${state.hits}/${state.total}" +
                            if (state.missingIds.isEmpty()) "" else "，${state.missingIds.size} 项降级"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            TextButton(onClick = onOpen) { Text("查看") }
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

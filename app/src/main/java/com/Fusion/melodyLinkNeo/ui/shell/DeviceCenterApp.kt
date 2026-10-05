package com.fusion.melodyLinkNeo.ui.shell

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fusion.melodyLinkNeo.BtRemixApplication
import com.fusion.melodyLinkNeo.core.hook.api.HookGatewayState
import com.fusion.melodyLinkNeo.core.settings.AppSettings
import com.fusion.melodyLinkNeo.core.settings.ThemeMode
import com.fusion.melodyLinkNeo.ui.definitions.DefinitionsScreen
import com.fusion.melodyLinkNeo.ui.definitions.DefinitionsViewModel
import com.fusion.melodyLinkNeo.ui.definitions.PackageDetailScreen
import com.fusion.melodyLinkNeo.ui.devices.DeviceSessionScreen
import com.fusion.melodyLinkNeo.ui.devices.DevicesScreen
import com.fusion.melodyLinkNeo.ui.devices.DevicesViewModel
import com.fusion.melodyLinkNeo.ui.devices.SessionViewModel
import com.fusion.melodyLinkNeo.ui.devtools.ExplorerScreen
import com.fusion.melodyLinkNeo.ui.devtools.MelodyDiagnosticsRoute
import com.fusion.melodyLinkNeo.ui.devtools.StudioScreen
import com.fusion.melodyLinkNeo.ui.explorer.ExplorerViewModel
import com.fusion.melodyLinkNeo.ui.home.HomeScreen
import com.fusion.melodyLinkNeo.ui.home.HomeViewModel
import com.fusion.melodyLinkNeo.ui.settings.AboutScreen
import com.fusion.melodyLinkNeo.ui.settings.DeveloperScreen
import com.fusion.melodyLinkNeo.ui.settings.LogsScreen
import com.fusion.melodyLinkNeo.ui.settings.SettingsScreen
import com.fusion.melodyLinkNeo.ui.settings.SettingsViewModel
import com.fusion.melodyLinkNeo.ui.settings.UpdateSourceScreen
import com.fusion.melodyLinkNeo.ui.studio.StudioViewModel
import com.fusion.melodyLinkNeo.ui.theme.BtRemixTheme
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * Root of the product UI (DEVICE_CENTER_UI_PLAN §6/§7).
 *
 * One [NavHost] with four tab roots plus their detail routes, a persistent [LiquidGlassNavBar], and
 * the theme driven by persisted settings so the Settings page can change it live.
 */
@Composable
fun DeviceCenterApp(
    initialRoute: String? = null,
    onInitialRouteConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as BtRemixApplication }
    val settings by app.settings.settings.collectAsState(initial = AppSettings())

    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    BtRemixTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
        DeviceCenterShell(
            app = app,
            settings = settings,
            initialRoute = initialRoute,
            onInitialRouteConsumed = onInitialRouteConsumed,
        )
    }
}

@Composable
private fun DeviceCenterShell(
    app: BtRemixApplication,
    settings: AppSettings,
    initialRoute: String?,
    onInitialRouteConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backdrop: LayerBackdrop = rememberLayerBackdrop()
    val stateHolder = rememberSaveableStateHolder()
    val hookState by app.hookGateway.state.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = AppTab.of(currentRoute)

    val lowRam = remember {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.isLowRamDevice == true
    }
    val glassEnabled = !settings.reduceTransparency && !lowRam

    fun selectTab(tab: AppTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(initialRoute) {
        if (initialRoute == Routes.MELODY_PAGE) {
            navController.navigate(Routes.MELODY_DIAGNOSTICS)
            onInitialRouteConsumed()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            HostUpdateBanner(
                state = hookState,
                onOpen = {
                    app.moduleStatus.refresh()
                    navController.navigate(Routes.MELODY_DIAGNOSTICS)
                },
            )
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
            ) {
                composable(Routes.HOME) {
                    stateHolder.SaveableStateProvider(Routes.HOME) {
                        val homeViewModel: HomeViewModel = viewModel()
                        val homeState by homeViewModel.state.collectAsState()
                        HomeScreen(
                            state = homeState,
                            onRefresh = homeViewModel::refresh,
                            onOpenDiagnostics = { navController.navigate(Routes.MELODY_DIAGNOSTICS) },
                            onOpenDevices = { selectTab(AppTab.DEVICES) },
                            onOpenDefinitions = { selectTab(AppTab.DEFINITIONS) },
                        )
                    }
                }

                composable(Routes.DEVICES) {
                    stateHolder.SaveableStateProvider(Routes.DEVICES) {
                        val devicesViewModel: DevicesViewModel = viewModel()
                        val devicesState by devicesViewModel.state.collectAsState()
                        val lifecycleOwner = LocalLifecycleOwner.current
                        val permissionLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestMultiplePermissions(),
                        ) { result ->
                            devicesViewModel.updatePermission(result.values.all { it })
                        }
                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    devicesViewModel.updatePermission(devicesViewModel.requiredPermissions().all {
                                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                                    })
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }
                        LaunchedEffect(devicesState.pendingRoute) {
                            val mac = devicesState.pendingRoute
                            if (mac != null) {
                                devicesViewModel.consumeRoute()
                                navController.navigate(Routes.deviceSession(mac))
                            }
                        }
                        DevicesScreen(
                            state = devicesState,
                            artworkFor = { packageId -> app.artwork.artworkFor(packageId) },
                            onFilter = devicesViewModel::setFilter,
                            onRefresh = devicesViewModel::refresh,
                            onToggleScan = devicesViewModel::toggleScan,
                            onConnect = devicesViewModel::connect,
                            onRequestPermission = { permissionLauncher.launch(devicesViewModel.requiredPermissions()) },
                            onDismissError = devicesViewModel::dismissError,
                        )
                    }
                }

                composable(
                    route = Routes.DEVICE_SESSION,
                    arguments = listOf(navArgument(Routes.DEVICE_SESSION_ARG) { type = NavType.StringType }),
                ) { entry ->
                    val mac = entry.arguments?.getString(Routes.DEVICE_SESSION_ARG).orEmpty()
                    val sessionViewModel: SessionViewModel = viewModel()
                    val sessionState by sessionViewModel.state.collectAsState()
                    LaunchedEffect(mac) { if (mac.isNotBlank()) sessionViewModel.open(mac) }
                    val leave: () -> Unit = {
                        sessionViewModel.leave(settings.backgroundRun)
                        navController.popBackStack()
                    }
                    BackHandler(enabled = true, onBack = leave)
                    DeviceSessionScreen(
                        state = sessionState,
                        developerMode = settings.developerMode,
                        onBack = leave,
                        onAction = sessionViewModel::execute,
                        onClearActionError = sessionViewModel::clearActionError,
                        onRetry = sessionViewModel::retry,
                    )
                }

                composable(Routes.DEFINITIONS) {
                    stateHolder.SaveableStateProvider(Routes.DEFINITIONS) {
                        val definitionsViewModel: DefinitionsViewModel = viewModel()
                        val definitionsState by definitionsViewModel.state.collectAsState()
                        DefinitionsScreen(
                            state = definitionsState,
                            onImport = definitionsViewModel::pick,
                            onToggle = definitionsViewModel::setEnabled,
                            onOpenDetail = { packageId -> navController.navigate(Routes.packageDetail(packageId)) },
                            onUninstall = definitionsViewModel::uninstall,
                            onCheckUpdate = definitionsViewModel::checkUpdate,
                            onConfirmInstall = definitionsViewModel::confirmInstall,
                            onConfirmReplace = definitionsViewModel::confirmReplace,
                            onDismissMessage = definitionsViewModel::dismissMessage,
                            onDismissError = definitionsViewModel::dismissError,
                        )
                    }
                }

                composable(
                    route = Routes.PACKAGE_DETAIL,
                    arguments = listOf(navArgument(Routes.PACKAGE_DETAIL_ARG) { type = NavType.StringType }),
                ) { entry ->
                    val packageId = entry.arguments?.getString(Routes.PACKAGE_DETAIL_ARG).orEmpty()
                    val definitionsViewModel: DefinitionsViewModel = viewModel()
                    val definitionsState by definitionsViewModel.state.collectAsState()
                    val pkg = definitionsState.packages.firstOrNull { it.packageId == packageId }
                    PackageDetailScreen(
                        packageId = packageId,
                        packages = definitionsState.packages,
                        onBack = { navController.popBackStack() },
                        onToggle = { definitionsViewModel.setEnabled(packageId, pkg?.enabled != true) },
                        onUninstall = {
                            definitionsViewModel.uninstall(packageId)
                            navController.popBackStack()
                        },
                        onCheckUpdate = definitionsViewModel::checkUpdate,
                    )
                }

                composable(Routes.SETTINGS) {
                    stateHolder.SaveableStateProvider(Routes.SETTINGS) {
                        val settingsViewModel: SettingsViewModel = viewModel()
                        val current by settingsViewModel.settings.collectAsState()
                        SettingsScreen(
                            settings = current,
                            version = com.fusion.melodyLinkNeo.BuildConfig.VERSION_NAME,
                            onToggleLogging = settingsViewModel::setLoggingEnabled,
                            onLogLevel = settingsViewModel::setLogLevel,
                            onLogRetention = settingsViewModel::setLogRetention,
                            onOpenLogs = { navController.navigate(Routes.LOGS) },
                            onUpdateSource = { navController.navigate(Routes.UPDATE_SOURCE) },
                            onAutoUpdate = settingsViewModel::setAutoUpdate,
                            onCheckUpdate = settingsViewModel::checkUpdateNow,
                            onStartOnBoot = settingsViewModel::setStartOnBoot,
                            onAutoRestoreSession = settingsViewModel::setAutoRestoreSession,
                            onBackgroundRun = settingsViewModel::setBackgroundRun,
                            onDynamicColor = settingsViewModel::setDynamicColor,
                            onThemeMode = settingsViewModel::setThemeMode,
                            onAbout = { navController.navigate(Routes.ABOUT) },
                            onDeveloper = { navController.navigate(Routes.DEVELOPER) },
                        )
                    }
                }

                composable(Routes.LOGS) {
                    val settingsViewModel: SettingsViewModel = viewModel()
                    val logs by settingsViewModel.logs.collectAsState()
                    LogsScreen(
                        logs = logs,
                        onClear = settingsViewModel::clearLogs,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.UPDATE_SOURCE) {
                    UpdateSourceScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.ABOUT) {
                    AboutScreen(
                        appVersion = com.fusion.melodyLinkNeo.BuildConfig.VERSION_NAME,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.DEVELOPER) {
                    val settingsViewModel: SettingsViewModel = viewModel()
                    val current by settingsViewModel.settings.collectAsState()
                    DeveloperScreen(
                        developerMode = current.developerMode,
                        onToggleDeveloperMode = settingsViewModel::setDeveloperMode,
                        onOpenExplorer = { navController.navigate(Routes.EXPLORER) },
                        onOpenStudio = { navController.navigate(Routes.STUDIO) },
                        onOpenMelodyDiagnostics = { navController.navigate(Routes.MELODY_DIAGNOSTICS) },
                        onOpenLogs = { navController.navigate(Routes.LOGS) },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.EXPLORER) {
                    val explorerViewModel: ExplorerViewModel = viewModel()
                    Column(Modifier.fillMaxSize()) {
                        BackBar(title = "BLE Explorer", onBack = { navController.popBackStack() })
                        ExplorerScreen(explorerViewModel, Modifier.weight(1f))
                    }
                }

                composable(Routes.STUDIO) {
                    val studioViewModel: StudioViewModel = viewModel()
                    Column(Modifier.fillMaxSize()) {
                        BackBar(title = "定义 Studio", onBack = { navController.popBackStack() })
                        StudioScreen(studioViewModel, Modifier.weight(1f))
                    }
                }

                composable(Routes.MELODY_DIAGNOSTICS) {
                    Column(Modifier.fillMaxSize()) {
                        BackBar(title = "Melody 诊断", onBack = { navController.popBackStack() })
                        MelodyDiagnosticsRoute(Modifier.weight(1f))
                    }
                }
            }
        }

        LiquidGlassNavBar(
            backdrop = backdrop,
            items = AppTab.entries,
            selected = currentTab,
            onSelect = ::selectTab,
            glassEnabled = glassEnabled,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun BackBar(title: String, onBack: () -> Unit) {
    com.fusion.melodyLinkNeo.ui.components.DcTopBar(title = title, onBack = onBack)
}

/**
 * Kept from the developer-tools era: a slim banner shown while a Melody update has not been
 * acknowledged, opening the anchor report.
 */
@Composable
private fun HostUpdateBanner(state: HookGatewayState, onOpen: () -> Unit) {
    if (!state.updateDetected) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.small,
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
                        "已重新定位锚点 ${state.coverage.hits}/${state.coverage.total}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            TextButton(onClick = onOpen) { Text("查看") }
        }
    }
}

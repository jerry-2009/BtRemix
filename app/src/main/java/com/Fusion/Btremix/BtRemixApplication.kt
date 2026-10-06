package com.Fusion.Btremix

import android.app.Application
import android.content.Intent
import com.Fusion.Btremix.core.activity.ActivityKind
import com.Fusion.Btremix.core.activity.ActivityRepository
import com.Fusion.Btremix.core.bluetooth.BleRepository
import com.Fusion.Btremix.core.bluetooth.android.AndroidBleManager
import com.Fusion.Btremix.core.classic.android.AndroidClassicConnectionMonitor
import com.Fusion.Btremix.core.classic.android.AndroidRfcommManager
import com.Fusion.Btremix.core.classic.api.ClassicConnectionMonitor
import com.Fusion.Btremix.core.classic.api.RfcommManager
import com.Fusion.Btremix.core.hook.ModuleStatusRepository
import com.Fusion.Btremix.core.logging.InMemoryLogger
import com.Fusion.Btremix.core.settings.SettingsRepository
import com.Fusion.Btremix.core.settings.settingsDataStore
import com.Fusion.Btremix.definition.loader.AndroidAssetBuiltInDefinitionSource
import com.Fusion.Btremix.definition.packages.DevicePackageBootstrap
import com.Fusion.Btremix.definition.packages.DevicePackageManager
import com.Fusion.Btremix.definition.packages.DevicePackageRepository
import com.Fusion.Btremix.definition.packages.DevicePackageStore
import com.Fusion.Btremix.definition.packages.InstalledMetadataStore
import com.Fusion.Btremix.definition.session.DefinitionSessionFactory
import com.Fusion.Btremix.device.registry.AndroidDeviceArtworkProvider
import com.Fusion.Btremix.device.registry.AndroidDeviceDiscoverySource
import com.Fusion.Btremix.device.registry.DeviceArtworkProvider
import com.Fusion.Btremix.device.registry.DeviceDiscoverySource
import com.Fusion.Btremix.device.registry.DeviceRegistry
import com.Fusion.Btremix.device.registry.batteryPercentOf
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceRuntime
import com.Fusion.Btremix.device.session.AutoSessionConnector
import com.Fusion.Btremix.device.session.AutoSessionEvent
import com.Fusion.Btremix.device.session.AutoSessionService
import com.Fusion.Btremix.device.session.DeviceSessionOpener
import com.Fusion.Btremix.device.session.HoldReason
import com.Fusion.Btremix.device.session.SessionManager
import com.Fusion.Btremix.device.session.SessionRegistry
import com.Fusion.Btremix.melody.api.MelodyCallPolicy
import com.Fusion.Btremix.melody.bridge.MelodyBridgeLog
import com.Fusion.Btremix.melody.bridge.MelodyHookGateway
import com.Fusion.Btremix.melody.bridge.MelodyHostUpdateTracker
import com.Fusion.Btremix.melody.bridge.MelodySessionService
import com.Fusion.Btremix.melody.config.MelodySupportRegistry
import com.Fusion.Btremix.melody.projection.AndroidMelodyTemplateSource
import com.Fusion.Btremix.melody.projection.MelodyCapabilityDebug
import com.Fusion.Btremix.melody.projection.MelodyProjectionBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Process-scoped owner of the device package registry and the session-creation material.
 *
 * The BLE Explorer, the Packages developer tools and (from M2) the Melody bridge must observe the
 * same registry, so the store, manager and bootstrap live here instead of inside a ViewModel.
 * Loading happens once per process; restarting the app re-scans the private package directory,
 * matching the milestone 1 policy.
 *
 * MELODY_BRIDGE_SPEC §11.2/§12 M2a also makes this class the holder of every session-creation
 * material - the BLE repository, RFCOMM manager, device runtime and definition factory - so the
 * Compose UI and the Melody bridge share one [SessionRegistry] instead of each opening their own
 * connection. The Explorer ViewModel only borrows these; it no longer constructs them.
 */
class BtRemixApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val packageBootstrap: DevicePackageBootstrap by lazy {
        DevicePackageBootstrap(
            manager = DevicePackageManager(
                DevicePackageStore(File(filesDir, DevicePackageStore.DIRECTORY_NAME)),
            ),
            builtIns = AndroidAssetBuiltInDefinitionSource(assets),
        )
    }

    /** The shared package manager used by every screen in the process. */
    val packages: DevicePackageManager get() = packageBootstrap.manager

    /**
     * Product-facing package surface: enable/disable, install metadata, and the filtered list that
     * `DeviceRegistry` and the Melody projection both consume (DEVICE_CENTER_UI_PLAN §3.5C).
     */
    val packageRepository: DevicePackageRepository by lazy {
        DevicePackageRepository(
            manager = packages,
            metadataStore = InstalledMetadataStore(
                File(packageDirectory(), InstalledMetadataStore.FILE_NAME),
            ),
            scope = scope,
        )
    }

    private fun packageDirectory(): File =
        File(filesDir, DevicePackageStore.DIRECTORY_NAME)

    /** Structured log bus shared by the BLE Explorer and (later) the Melody bridge. */
    val logger: InMemoryLogger = InMemoryLogger()

    /** Home "最近活动": merges the transport log with explicit package/hook events. */
    val activity: ActivityRepository by lazy { ActivityRepository(logger) }

    /** Persisted product settings (theme, logging, module behaviour). */
    val settings: SettingsRepository by lazy { SettingsRepository(settingsDataStore) }

    /** Neutral hook view for the UI; the only implementation targets ColorOS Melody (D-UI-6). */
    val hookGateway: MelodyHookGateway by lazy { MelodyHookGateway(this, scope) }
    val moduleStatus: ModuleStatusRepository by lazy { ModuleStatusRepository(hookGateway, scope) }

    /** BLE transport, repository-scoped so logs and connection state outlive one screen. */
    val bleRepository: BleRepository by lazy { BleRepository(AndroidBleManager(this), logger) }

    /** Classic Bluetooth (RFCOMM/SPP) transport backend. */
    val rfcomm: RfcommManager by lazy { AndroidRfcommManager(this) }

    /** Device runtime and the Definition->session wiring layer, reused for every session. */
    val deviceRuntime: DeviceRuntime by lazy { DefaultDeviceRuntime() }
    val sessionFactory: DefinitionSessionFactory by lazy { DefinitionSessionFactory(deviceRuntime) }

    /**
     * Process-scoped session ownership (MELODY_BRIDGE_SPEC §3.3/§11.2).
     *
     * The Compose UI acquires/releases through it, and the Melody bridge acquires the same instance,
     * which is what makes "both front-ends share exactly one control channel" true by construction.
     */
    val sessions: SessionRegistry = SessionRegistry(scope)

    /** UI-facing session view: polled snapshots plus the "会话时间" anchor (D-UI-3). */
    val sessionManager: SessionManager by lazy { SessionManager(sessions, scope) }

    /**
     * Classic Bluetooth link monitor used as the auto-session trigger source
     * (HANDOFF_AUTO_SESSION.md §6 step 1). Process-scoped so the service can start/stop it.
     */
    val classicConnections: ClassicConnectionMonitor by lazy { AndroidClassicConnectionMonitor(this) }

    /**
     * Opens a session without registering it, so the auto-connect hold can own the single registry
     * reference (HANDOFF_AUTO_SESSION.md §6 step 3).
     */
    private val autoSessionOpener: DeviceSessionOpener by lazy {
        DeviceSessionOpener(
            ble = bleRepository,
            rfcomm = rfcomm,
            runtime = deviceRuntime,
            sessionFactory = sessionFactory,
            packages = packages.registry,
        )
    }

    /** Bonded classic + optional BLE scan discovery feeding the Devices page. */
    val deviceDiscovery: DeviceDiscoverySource by lazy { AndroidDeviceDiscoverySource(rfcomm, bleRepository, scope) }

    /** `DevicePackage × discovered instance → DeviceEntry` for the Devices page. */
    val deviceRegistry: DeviceRegistry by lazy {
        DeviceRegistry(
            packages = packageRepository.enabledPackages,
            enabledPackageIds = packageRepository.enabledPackageIds,
            discovery = deviceDiscovery,
            sessions = sessionManager.snapshots,
            scope = scope,
            batteryOf = { _, snapshot -> batteryPercentOf(snapshot) },
        )
    }

    /** Fixed `assets/icon.png` reader with monogram fallback handled by the UI (D-UI-5). */
    val artwork: DeviceArtworkProvider by lazy {
        AndroidDeviceArtworkProvider(
            assets = assets,
            packageDirectory = packageDirectory(),
            packages = { packages.registry.all() },
        )
    }

    private val bridgeLog = MelodyBridgeLog()

    /**
     * "Which paired devices does Melody have to see as supported?" (HANDOFF_MELODY_M3_PLAN.md §4 M3.1).
     * Device packages provide the Definitions with a `melody` section; the RFCOMM backend provides the
     * bonded classic devices. Keeping this process-scoped lets the service start for a paired-but-idle
     * headset, before any session exists (M3-D6).
     */
    val melodySupport: MelodySupportRegistry by lazy {
        MelodySupportRegistry(
            // Only enabled packages project into Melody: disabling a package must stop the panel
            // from offering it without restarting either process (§3.5C).
            packages = packageRepository.enabledPackages,
            rfcomm = rfcomm,
            scope = scope,
            onManagedChanged = { macs ->
                bridgeLog.event("melody.managed.refresh", "count" to macs.size, "macs" to macs.joinToString(","))
            },
        )
    }

    /** Synthetic whitelist envelope builder; reads the M3.-1 template from the APK assets. */
    val melodyProjection: MelodyProjectionBuilder by lazy {
        MelodyProjectionBuilder(
            templates = AndroidMelodyTemplateSource(assets),
            capabilityOverrides = melodyCapabilityDebug,
        )
    }

    /**
     * M4.1 experiment only: raw capability bits forced from `files/melody-capability-debug.txt`.
     * Always empty in a release build ([MelodyCapabilityDebug.read]), so shipping behaviour is the
     * rule table alone.
     *
     * Lazily initialised on purpose: an `Application` property initialiser runs before
     * `attachBaseContext`, where `filesDir` is not available yet.
     */
    private val melodyCapabilityDebug by lazy {
        MelodyCapabilityDebug.read(
            debugBuild = BuildConfig.DEBUG,
            file = File(filesDir, MelodyCapabilityDebug.FILE_NAME),
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (melodyCapabilityDebug.isNotEmpty()) {
            bridgeLog.event(
                "melody.capability.debug",
                "count" to melodyCapabilityDebug.size,
                "keys" to melodyCapabilityDebug.entries.joinToString(",") { "${it.key}=${it.value}" },
            )
        }
        scope.launch { packageBootstrap.load() }
        melodySupport.start()
        watchSessionsForMelodyBridge()
        // The switch is persisted, so a process that starts in the foreground has to bring the
        // auto-session foreground service back up (HANDOFF_AUTO_SESSION.md §6 step 4). A background
        // start is refused by the platform; that is logged and retried on the next visible start.
        scope.launch {
            val autoSessionOn = runCatching {
                settings.settings.first().autoSessionOnBluetoothConnect
            }.getOrDefault(false)
            if (autoSessionOn) startAutoSessionService()
        }
        // M6: detect a Melody update (install fingerprint changed) and arm the in-app/system prompt.
        runCatching { MelodyHostUpdateTracker.refresh(this) }
    }

    /**
     * Builds the auto-session connector for [AutoSessionService]
     * (HANDOFF_AUTO_SESSION.md §5). Kept here so the service stays a thin Android shell and the
     * connector can be exercised from JVM tests with fakes.
     */
    fun createAutoSessionConnector(
        scope: CoroutineScope,
        monitor: ClassicConnectionMonitor,
        enabled: StateFlow<Boolean>,
    ): AutoSessionConnector = AutoSessionConnector(
        monitor = monitor,
        devices = deviceRegistry.devices,
        sessions = sessionManager,
        packageRegistry = packages.registry,
        enabled = enabled,
        open = { mac, name, definition -> autoSessionOpener.openSession(mac, name, definition) },
        scope = scope,
        onEvent = ::logAutoSessionEvent,
    )

    /** Starts the auto-session foreground service; called from the Settings switch and app start. */
    fun startAutoSessionService() {
        val result = runCatching { startForegroundService(Intent(this, AutoSessionService::class.java)) }
        if (result.isSuccess) {
            bridgeLog.event("auto.session.service_start_requested")
        } else {
            bridgeLog.warn("auto.session.service_start_denied", result.exceptionOrNull())
        }
    }

    /** Stops the service; its `onDestroy` releases every auto hold. */
    fun stopAutoSessionService() {
        runCatching { stopService(Intent(this, AutoSessionService::class.java)) }
            .onFailure { bridgeLog.warn("auto.session.service_stop_failed", it) }
    }

    /** Releases auto-connect holds outside the service lifecycle (e.g. service death races). */
    fun releaseAutoSessionHolds() {
        scope.launch { runCatching { sessionManager.releaseHolds(HoldReason.AUTO_CONNECT) } }
    }

    private fun logAutoSessionEvent(event: AutoSessionEvent) {
        when (event) {
            is AutoSessionEvent.Opened -> {
                bridgeLog.event(
                    "auto.session.opened",
                    "mac" to event.mac,
                    "package" to event.packageId,
                )
                activity.record(
                    kind = ActivityKind.CONNECTION,
                    title = "自动建立会话",
                    detail = event.packageId,
                    deviceId = event.mac,
                    packageId = event.packageId,
                )
            }
            is AutoSessionEvent.Failed -> bridgeLog.warn("auto.session.failed", event.error)

            is AutoSessionEvent.Released -> bridgeLog.event(
                "auto.session.released",
                "mac" to event.mac,
                "reason" to event.reason,
            )

            is AutoSessionEvent.Skipped -> bridgeLog.event(
                "auto.session.skipped",
                "mac" to event.mac,
                "reason" to event.reason,
            )

            is AutoSessionEvent.Link -> bridgeLog.event(
                "auto.session.link",
                "mac" to event.mac,
                "connected" to event.connected,
            )
        }
    }

    /**
     * Starts the Melody bridge service while there is a session *or* a managed device
     * (MELODY_BRIDGE_TRANSPORT_PLAN.md §7 strategy b, M3-D6).
     *
     * The host cannot bind to the service (package visibility, §8.1), so BtRemix has to announce itself.
     * Tying the service to the session/managed-device lifetime is what keeps the bridge from being a
     * permanent background service: a connected *or* paired headset is exactly when the Melody panel can
     * show anything, and the service stops itself again once neither remains and no client is bound.
     */
    private fun watchSessionsForMelodyBridge() {
        scope.launch {
            combine(sessions.managedMacsFlow, melodySupport.managedMacsFlow) { live, managed ->
                (live + managed).distinct()
            }.collect { macs ->
                if (macs.isNotEmpty()) startMelodyBridgeService(macs.size)
            }
        }
    }

    private fun startMelodyBridgeService(deviceCount: Int) {
        if (!hasMelodyHost()) {
            // Silent before: a host that is actually installed but filtered out by package visibility
            // looked exactly like "BtRemix was never started". One line is enough to tell them apart.
            bridgeLog.event(
                "melody.bridge.service_skipped",
                "reason" to "no_host",
                "devices" to deviceCount,
            )
            return
        }
        val result = runCatching {
            startForegroundService(Intent(this, MelodySessionService::class.java))
        }
        if (result.isSuccess) {
            bridgeLog.event("melody.bridge.service_start_requested", "devices" to deviceCount)
        } else {
            bridgeLog.warn("melody.bridge.service_start_failed", result.exceptionOrNull())
        }
    }

    /** The bridge is only useful when ColorOS Melody is installed; the `<queries>` entry makes this lookup legal. */
    private fun hasMelodyHost(): Boolean = runCatching {
        packageManager.getPackageInfo(MelodyCallPolicy.HOST_PACKAGE, 0)
    }.isSuccess
}

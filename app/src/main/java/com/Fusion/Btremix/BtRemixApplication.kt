package com.Fusion.Btremix

import android.app.Application
import android.content.Intent
import com.Fusion.Btremix.core.bluetooth.BleRepository
import com.Fusion.Btremix.core.bluetooth.android.AndroidBleManager
import com.Fusion.Btremix.core.classic.android.AndroidRfcommManager
import com.Fusion.Btremix.core.classic.api.RfcommManager
import com.Fusion.Btremix.core.logging.InMemoryLogger
import com.Fusion.Btremix.definition.loader.AndroidAssetBuiltInDefinitionSource
import com.Fusion.Btremix.definition.packages.DevicePackageBootstrap
import com.Fusion.Btremix.definition.packages.DevicePackageManager
import com.Fusion.Btremix.definition.packages.DevicePackageStore
import com.Fusion.Btremix.definition.session.DefinitionSessionFactory
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceRuntime
import com.Fusion.Btremix.device.session.SessionRegistry
import com.Fusion.Btremix.melody.api.MelodyCallPolicy
import com.Fusion.Btremix.melody.bridge.MelodyBridgeLog
import com.Fusion.Btremix.melody.bridge.MelodySessionService
import com.Fusion.Btremix.melody.bridge.MelodyHostUpdateTracker
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

    /** Structured log bus shared by the BLE Explorer and (later) the Melody bridge. */
    val logger: InMemoryLogger = InMemoryLogger()

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
     * M0 introduced the holder; M2a upgrades it to the sole reference-counted owner. The Compose UI
     * acquires/releases through it, and the Melody bridge will acquire the same instance in M2b.
     * Teardown runs on the application scope so a released session can close after the owning
     * ViewModel is gone.
     */
    val sessions: SessionRegistry = SessionRegistry(scope)

    private val bridgeLog = MelodyBridgeLog()

    /**
     * "Which paired devices does Melody have to see as supported?" (HANDOFF_MELODY_M3_PLAN.md §4 M3.1).
     * Device packages provide the Definitions with a `melody` section; the RFCOMM backend provides the
     * bonded classic devices. Keeping this process-scoped lets the service start for a paired-but-idle
     * headset, before any session exists (M3-D6).
     */
    val melodySupport: MelodySupportRegistry by lazy {
        MelodySupportRegistry(
            packages = packages.packages,
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
        // M6: detect a Melody update (install fingerprint changed) and arm the in-app/system prompt.
        runCatching { MelodyHostUpdateTracker.refresh(this) }
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

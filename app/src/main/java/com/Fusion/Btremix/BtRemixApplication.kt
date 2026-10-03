package com.Fusion.Btremix

import android.app.Application
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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    override fun onCreate() {
        super.onCreate()
        scope.launch { packageBootstrap.load() }
    }
}

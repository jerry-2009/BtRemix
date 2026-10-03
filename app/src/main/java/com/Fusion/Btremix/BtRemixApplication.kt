package com.Fusion.Btremix

import android.app.Application
import com.Fusion.Btremix.definition.loader.AndroidAssetBuiltInDefinitionSource
import com.Fusion.Btremix.definition.packages.DevicePackageBootstrap
import com.Fusion.Btremix.definition.packages.DevicePackageManager
import com.Fusion.Btremix.definition.packages.DevicePackageStore
import com.Fusion.Btremix.device.session.SessionRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-scoped owner of the device package registry.
 *
 * The BLE Explorer and the Packages developer tools must observe the same registry, so the store,
 * manager and bootstrap live here instead of inside a ViewModel. Loading happens once per process;
 * restarting the app re-scans the private package directory, matching the milestone 1 policy.
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
     * Process-scoped session ownership (MELODY_BRIDGE_SPEC §3.3/§11.2).
     *
     * M0 only introduces the holder so the Compose UI and the Melody bridge can later share one
     * `ProtocolSession` per MAC. Nothing consumes it yet; wiring happens in M2.
     */
    val sessions: SessionRegistry = SessionRegistry()

    override fun onCreate() {
        super.onCreate()
        scope.launch { packageBootstrap.load() }
    }
}

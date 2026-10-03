package com.Fusion.Btremix

import android.app.Application
import com.Fusion.Btremix.definition.loader.AndroidAssetBuiltInDefinitionSource
import com.Fusion.Btremix.definition.packages.DevicePackageBootstrap
import com.Fusion.Btremix.definition.packages.DevicePackageManager
import com.Fusion.Btremix.definition.packages.DevicePackageStore
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

    override fun onCreate() {
        super.onCreate()
        scope.launch { packageBootstrap.load() }
    }
}

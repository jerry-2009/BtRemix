package com.fusion.melodyLinkNeo.definition.packages

import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.definition.loader.BuiltInDefinitionSource
import com.fusion.melodyLinkNeo.definition.loader.DefinitionLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A package-loading failure paired with the source it came from. */
data class PackageLoadFailure(val sourceName: String?, val error: PackageError)

/**
 * Loads built-in definitions and installed `.dcpkg` files into a shared [DevicePackageManager].
 *
 * The bootstrap hides where built-ins come from: the app passes an asset-backed source, JVM tests
 * pass strings. [ready] flips to `true` after the first load finishes so the UI can distinguish
 * "no packages" from "not loaded yet"; a failure never blocks the remaining packages.
 */
class DevicePackageBootstrap(
    val manager: DevicePackageManager,
    private val builtIns: BuiltInDefinitionSource,
    private val loader: DefinitionLoader = DefinitionLoader(),
) {
    val registry: DevicePackageRegistry get() = manager.registry

    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    private val mutableFailures = MutableStateFlow<List<PackageLoadFailure>>(emptyList())
    val failures: StateFlow<List<PackageLoadFailure>> = mutableFailures.asStateFlow()

    /** Re-reads built-ins and installed packages. Safe to call repeatedly and from any thread. */
    @Synchronized
    fun load(): List<PackageLoadFailure> {
        val failures = mutableListOf<PackageLoadFailure>()
        failures += registerBuiltIns()
        failures += manager.refreshInstalled().map { PackageLoadFailure(null, it) }
        mutableFailures.value = failures
        mutableReady.value = true
        return failures
    }

    /** Loads only while the first load has not completed yet. */
    fun loadIfNeeded() {
        if (!ready.value) load()
    }

    private fun registerBuiltIns(): List<PackageLoadFailure> {
        val failures = mutableListOf<PackageLoadFailure>()
        val definitions = mutableListOf<LoadedDeviceDefinition>()
        builtIns.list().forEach { name ->
            runCatching { loader.load(builtIns.read(name)) }
                .onSuccess(definitions::add)
                .onFailure { failures += PackageLoadFailure(name, it.asPackageError(name)) }
        }
        failures += manager.registerBuiltIns(definitions).map { PackageLoadFailure(null, it) }
        return failures
    }
}

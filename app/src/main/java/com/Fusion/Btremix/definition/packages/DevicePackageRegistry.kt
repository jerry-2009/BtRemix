package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.matcher.DefinitionMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the set of loaded device packages, keyed by `packageId`.
 *
 * A single active version per id is allowed. Registering an installed package over a built-in
 * package is always rejected; replacing an installed package requires `allowReplace = true`.
 */
class DevicePackageRegistry {
    private val lock = Any()
    private val mutablePackages = MutableStateFlow<List<DevicePackage>>(emptyList())

    /** All registered packages, built-ins first, then installed packages by id. */
    val packages: StateFlow<List<DevicePackage>> = mutablePackages.asStateFlow()

    fun all(): List<DevicePackage> = mutablePackages.value

    fun find(packageId: String): DevicePackage? = mutablePackages.value.firstOrNull { it.packageId == packageId }

    fun register(packageToRegister: DevicePackage, allowReplace: Boolean = false): DevicePackage = synchronized(lock) {
        val current = mutablePackages.value
        val existing = current.firstOrNull { it.packageId == packageToRegister.packageId }
        if (existing != null) {
            if (existing.isBuiltIn && !packageToRegister.isBuiltIn) {
                throw PackageException(
                    PackageError.PackageConflict(
                        packageId = packageToRegister.packageId,
                        detail = "cannot replace built-in package '${packageToRegister.packageId}'",
                        path = "package.json.id",
                    ),
                )
            }
            if (!allowReplace) {
                throw PackageException(
                    PackageError.PackageConflict(
                        packageId = packageToRegister.packageId,
                        detail = "package '${packageToRegister.packageId}' is already registered",
                        path = "package.json.id",
                    ),
                )
            }
        }
        publish(current.filterNot { it.packageId == packageToRegister.packageId } + packageToRegister)
        packageToRegister
    }

    /**
     * Atomically replaces all installed packages while preserving built-ins. Used by startup
     * reload so that a corrupt package dropping out cannot leave a stale registration behind.
     */
    fun replaceInstalled(packages: List<DevicePackage>): List<DevicePackage> = synchronized(lock) {
        val builtIns = mutablePackages.value.filter { it.isBuiltIn }
        val builtInIds = builtIns.mapTo(mutableSetOf()) { it.packageId }
        val installed = packages
            .filter { !it.isBuiltIn }
            .filterNot { it.packageId in builtInIds }
        publish(builtIns + installed)
    }

    fun remove(packageId: String): Boolean = synchronized(lock) {
        val existing = mutablePackages.value.firstOrNull { it.packageId == packageId } ?: return false
        if (existing.isBuiltIn) {
            throw PackageException(
                PackageError.PackageConflict(
                    packageId = packageId,
                    detail = "cannot remove built-in package '$packageId'",
                ),
            )
        }
        publish(mutablePackages.value.filterNot { it.packageId == packageId })
        true
    }

    fun clear() = synchronized(lock) { publish(emptyList()) }

    fun definitions(): List<LoadedDeviceDefinition> = mutablePackages.value.map { it.definition }

    /** Finds the highest-priority matching package for a scan result, preserving registry order on ties. */
    fun findMatch(scan: BleScanResult): DevicePackage? = mutablePackages.value
        .asSequence()
        .mapNotNull { packageToCheck ->
            DefinitionMatcher.rank(packageToCheck.definition, scan)?.let { packageToCheck to it.priority }
        }
        .maxByOrNull { it.second }
        ?.first

    private fun publish(packages: List<DevicePackage>): List<DevicePackage> {
        val sorted = packages.sortedWith(
            compareBy({ it.source.ordinal }, { it.packageId }),
        )
        mutablePackages.value = sorted
        return sorted
    }
}

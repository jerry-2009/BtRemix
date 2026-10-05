package com.fusion.melodyLinkNeo.definition.packages

import com.fusion.melodyLinkNeo.core.bluetooth.api.BleScanResult
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleService
import com.fusion.melodyLinkNeo.definition.api.DeviceMatchInput
import com.fusion.melodyLinkNeo.definition.api.DeviceMatchRule
import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.definition.matcher.DefinitionMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A matching package together with the rule and priority that produced the match. */
data class DevicePackageMatch(
    val devicePackage: DevicePackage,
    val priority: Int,
    val rule: DeviceMatchRule,
)

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

    /**
     * Finds the highest-priority matching package for a neutral input (MELODY_BRIDGE_SPEC §11.3) and
     * reports which rule matched, preserving registry order on ties. The extra detail lets the UI (and
     * the Melody registry) show why a device was recognised instead of silently applying a definition.
     */
    fun match(input: DeviceMatchInput): DevicePackageMatch? = mutablePackages.value
        .asSequence()
        .mapNotNull { packageToCheck ->
            DefinitionMatcher.rank(packageToCheck.definition, input)?.let { match ->
                DevicePackageMatch(packageToCheck, match.priority, match.rule)
            }
        }
        .maxByOrNull { it.priority }

    /** Scan-time convenience; equivalent to matching [DeviceMatchInput.Advertisement]. */
    fun match(scan: BleScanResult): DevicePackageMatch? = match(DeviceMatchInput.Advertisement(scan))

    /**
     * Connect-time match over discovered GATT services, used when the advertised scan data did not
     * identify the device. Ties keep registry order, like [match].
     */
    fun match(services: List<BleService>): DevicePackageMatch? = mutablePackages.value
        .asSequence()
        .mapNotNull { packageToCheck ->
            DefinitionMatcher.rankServices(packageToCheck.definition, services)?.let { match ->
                DevicePackageMatch(packageToCheck, match.priority, match.rule)
            }
        }
        .maxByOrNull { it.priority }

    /** Finds the highest-priority matching package for a scan result. */
    fun findMatch(scan: BleScanResult): DevicePackage? = match(scan)?.devicePackage

    /** Finds the highest-priority matching package for a neutral input. */
    fun findMatch(input: DeviceMatchInput): DevicePackage? = match(input)?.devicePackage

    private fun publish(packages: List<DevicePackage>): List<DevicePackage> {
        val sorted = packages.sortedWith(
            compareBy({ it.source.ordinal }, { it.packageId }),
        )
        mutablePackages.value = sorted
        return sorted
    }
}

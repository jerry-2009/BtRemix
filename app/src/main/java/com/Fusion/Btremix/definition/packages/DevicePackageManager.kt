package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import kotlinx.coroutines.flow.StateFlow

/** Outcome of installing a `.dcpkg`. */
sealed interface PackageInstallResult {
    data class Installed(val packageInstalled: DevicePackage) : PackageInstallResult

    /** The id is already installed; the caller must confirm a replacement. */
    data class Conflict(val candidate: DevicePackage, val existing: DevicePackage) : PackageInstallResult
}

/**
 * Coordinates the [DevicePackageStore] and [DevicePackageRegistry] to provide load, install and
 * uninstall flows. Android-independent so it can be exercised from JVM tests.
 */
class DevicePackageManager(
    private val store: DevicePackageStore,
    private val reader: DevicePackageReader = DevicePackageReader(),
    private val validator: DevicePackageValidator = DevicePackageValidator(),
    val registry: DevicePackageRegistry = DevicePackageRegistry(),
) {
    val packages: StateFlow<List<DevicePackage>> get() = registry.packages

    /**
     * Registers built-in definitions as packages. Re-registering is idempotent, and a built-in
     * always wins over an installed package with the same id. Individual failures are reported.
     */
    fun registerBuiltIns(definitions: List<LoadedDeviceDefinition>): List<PackageError> {
        val errors = mutableListOf<PackageError>()
        definitions.forEach { definition ->
            runCatching { registry.register(DevicePackage.builtIn(definition), allowReplace = true) }
                .onFailure { errors += it.asPackageError(definition.manifest.id) }
        }
        return errors
    }

    /**
     * Reloads every `.dcpkg` in the private directory. A corrupt package is reported and skipped,
     * and never prevents the remaining packages from loading.
     */
    fun refreshInstalled(): List<PackageError> {
        val errors = mutableListOf<PackageError>()
        val loaded = store.list().mapNotNull { file ->
            runCatching { validator.validate(reader.read(file)) }
                .onFailure { errors += it.asPackageError(file.name) }
                .getOrNull()
        }
        registry.replaceInstalled(loaded)
        return errors
    }

    /** Reads and validates a package without touching the registry or private storage. */
    fun load(bytes: ByteArray, sourceName: String): DevicePackage =
        validator.validate(reader.read(bytes, sourceName))

    /**
     * Installs a validated package. Returns [PackageInstallResult.Conflict] when an installed
     * package already uses the id and `replace` is false.
     */
    fun install(bytes: ByteArray, sourceName: String, replace: Boolean = false): PackageInstallResult {
        val candidate = load(bytes, sourceName)
        val existing = registry.find(candidate.packageId)
        if (existing != null && existing.isBuiltIn) {
            throw PackageException(
                PackageError.PackageConflict(
                    packageId = candidate.packageId,
                    detail = "cannot replace built-in package '${candidate.packageId}'",
                    path = "package.json.id",
                ),
            )
        }
        if (existing != null && !replace) {
            return PackageInstallResult.Conflict(candidate, existing)
        }

        val backup = if (store.exists(candidate.packageId)) store.read(store.packageFile(candidate.packageId)) else null
        store.write(candidate.packageId, bytes)
        try {
            registry.register(candidate, allowReplace = true)
        } catch (error: Throwable) {
            if (backup != null) store.write(candidate.packageId, backup) else store.delete(candidate.packageId)
            throw error
        }
        return PackageInstallResult.Installed(candidate)
    }

    /** Removes an installed package from storage and the registry. Built-ins cannot be removed. */
    fun uninstall(packageId: String): Boolean {
        val existing = registry.find(packageId) ?: return false
        if (existing.isBuiltIn) {
            throw PackageException(
                PackageError.PackageConflict(packageId = packageId, detail = "cannot remove built-in package '$packageId'"),
            )
        }
        val deleted = store.delete(packageId)
        registry.remove(packageId)
        return deleted
    }
}

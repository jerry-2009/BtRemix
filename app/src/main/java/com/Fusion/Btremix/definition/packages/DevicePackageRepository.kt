package com.Fusion.Btremix.definition.packages

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A registered package plus this device's local installation state. */
data class InstalledPackage(
    val devicePackage: DevicePackage,
    val enabled: Boolean,
    val installedAt: Instant?,
    val source: String?,
    val updateAvailableVersion: String? = null,
) {
    val packageId: String get() = devicePackage.packageId
    val isBuiltIn: Boolean get() = devicePackage.isBuiltIn
}

/**
 * UI-facing wrapper around [DevicePackageManager] (DEVICE_CENTER_UI_PLAN §3.2).
 *
 * Owns the enable/disable flag and the install-time metadata that the manager knows nothing about,
 * and exposes one observable list the Definitions page and `DeviceRegistry` both consume. Enable
 * state is persisted through [InstalledMetadataStore]; install/uninstall delegate straight to the
 * manager so the `.dcpkg` lifecycle stays in one place.
 */
class DevicePackageRepository(
    private val manager: DevicePackageManager,
    private val metadataStore: InstalledMetadataStore,
    scope: CoroutineScope,
) {
    private val scope = scope
    private val metadata = MutableStateFlow(metadataStore.read())

    /** Every registered package (built-ins first), with local metadata merged in. */
    val packages: StateFlow<List<InstalledPackage>> =
        kotlinx.coroutines.flow.combine(manager.packages, metadata) { registered, local ->
            registered.map { pkg ->
                val entry = local[pkg.packageId]
                InstalledPackage(
                    devicePackage = pkg,
                    enabled = entry?.enabled ?: true,
                    installedAt = entry?.installedAt?.let(Instant::ofEpochMilli),
                    source = entry?.source ?: pkg.sourceName,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Only the packages that may take part in matching and Melody projection. */
    val enabledPackages: StateFlow<List<DevicePackage>> = packages
        .map { list -> list.filter { it.enabled }.map { it.devicePackage } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val enabledPackageIds: StateFlow<Set<String>> = packages
        .map { list -> list.filter { it.enabled }.mapTo(mutableSetOf()) { it.packageId } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun refresh() {
        manager.refreshInstalled()
    }

    fun setEnabled(packageId: String, enabled: Boolean) {
        val current = metadata.value.toMutableMap()
        val existing = current[packageId] ?: InstalledPackageMetadata(packageId)
        current[packageId] = existing.copy(enabled = enabled)
        metadata.value = current
        persist()
    }

    fun install(bytes: ByteArray, sourceName: String, replace: Boolean = false): PackageInstallResult {
        val result = manager.install(bytes, sourceName, replace)
        if (result is PackageInstallResult.Installed) {
            recordInstall(result.packageInstalled.packageId, sourceName)
        }
        return result
    }

    fun uninstall(packageId: String): Boolean {
        val removed = manager.uninstall(packageId)
        if (removed) {
            val current = metadata.value.toMutableMap()
            current.remove(packageId)
            metadata.value = current
            persist()
        }
        return removed
    }

    fun metadataOf(packageId: String): InstalledPackageMetadata? = metadata.value[packageId]

    private fun recordInstall(packageId: String, sourceName: String) {
        val current = metadata.value.toMutableMap()
        val existing = current[packageId]
        current[packageId] = InstalledPackageMetadata(
            packageId = packageId,
            enabled = existing?.enabled ?: true,
            installedAt = System.currentTimeMillis(),
            source = sourceName,
            updateSource = existing?.updateSource,
            pinnedVersion = existing?.pinnedVersion,
            lastCheckedAt = existing?.lastCheckedAt,
        )
        metadata.value = current
        persist()
    }

    private fun persist() {
        val snapshot = metadata.value
        scope.launch(Dispatchers.IO) {
            runCatching { metadataStore.write(snapshot) }
        }
    }

    fun reloadMetadata() {
        metadata.value = metadataStore.read()
    }
}

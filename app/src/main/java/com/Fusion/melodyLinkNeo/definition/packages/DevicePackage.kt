package com.fusion.melodyLinkNeo.definition.packages

import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition

/** Where a registered package came from. Built-in packages ship inside the APK. */
enum class DevicePackageSource { BUILT_IN, INSTALLED }

/** Supported `.dcpkg` container versions. */
object DevicePackageFormat {
    const val CURRENT: Int = 1
    val SUPPORTED: IntRange = 1..1
}

/**
 * Hard limits applied while reading a `.dcpkg`. Kept injectable so tests can exercise the
 * limit checks without materialising multi-megabyte archives.
 */
data class PackageLimits(
    val maxArchiveBytes: Long = 10L * 1024 * 1024,
    val maxTotalUncompressedBytes: Long = 25L * 1024 * 1024,
    val maxEntries: Int = 100,
    val maxSingleFileBytes: Long = 5L * 1024 * 1024,
)

/** A fully loaded and validated device package. */
data class DevicePackage(
    val packageId: String,
    val version: String,
    val sourceName: String,
    val definition: LoadedDeviceDefinition,
    val assets: List<String> = emptyList(),
    val source: DevicePackageSource = DevicePackageSource.INSTALLED,
    val packageFormat: Int = DevicePackageFormat.CURRENT,
) {
    val displayName: String get() = definition.manifest.displayName
    val capabilities: List<String> get() = definition.manifest.capabilities.toList()
    val matcherCount: Int get() = definition.manifest.matchers.size
    val isBuiltIn: Boolean get() = source == DevicePackageSource.BUILT_IN

    companion object {
        fun builtIn(
            definition: LoadedDeviceDefinition,
            sourceName: String = definition.manifest.id,
        ): DevicePackage = DevicePackage(
            packageId = definition.manifest.id,
            version = definition.manifest.version,
            sourceName = sourceName,
            definition = definition,
            assets = emptyList(),
            source = DevicePackageSource.BUILT_IN,
            packageFormat = DevicePackageFormat.CURRENT,
        )
    }
}

/**
 * Raw package contents as produced by [DevicePackageReader]: the archive has been safely
 * unpacked into memory, but package metadata and the Definition have not been validated yet.
 */
data class RawDevicePackage(
    val sourceName: String,
    val packageJson: String,
    val entryPath: String,
    val definitionJson: String,
    val assets: List<String>,
)

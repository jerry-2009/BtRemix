package com.fusion.melodyLinkNeo.device.registry

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.fusion.melodyLinkNeo.definition.packages.DevicePackage
import com.fusion.melodyLinkNeo.definition.packages.DevicePackageStore
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Supplies the device artwork shown on a device card (D-UI-5).
 *
 * The path is a fixed convention - `assets/icon.png` inside the package - and is deliberately not
 * declared in `manifest`, so no package can point the app at an arbitrary file or a network URL.
 * Returns `null` when the package has no icon; the UI then draws a monogram placeholder.
 */
interface DeviceArtworkProvider {
    fun artworkFor(packageId: String): ImageBitmap?
}

/** Android implementation: APK assets for built-ins, the `.dcpkg` zip for installed packages. */
class AndroidDeviceArtworkProvider(
    private val assets: AssetManager,
    private val packageDirectory: File,
    private val packages: () -> List<DevicePackage>,
) : DeviceArtworkProvider {

    private val cache = HashMap<String, ImageBitmap?>()

    override fun artworkFor(packageId: String): ImageBitmap? = synchronized(cache) {
        if (cache.containsKey(packageId)) return cache[packageId]
        val bitmap = runCatching { load(packageId) }.getOrNull()
        cache[packageId] = bitmap
        bitmap
    }

    fun invalidate() = synchronized(cache) { cache.clear() }

    private fun load(packageId: String): ImageBitmap? {
        val devicePackage = packages().firstOrNull { it.packageId == packageId } ?: return null
        val bytes = (if (devicePackage.isBuiltIn) builtInIcon(devicePackage) else installedIcon(packageId))
            ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    /**
     * Built-in definitions live at `assets/<dir>/<name>.json`; their artwork is looked up at
     * `assets/<dir>/<name>/assets/icon.png`, mirroring the in-package layout.
     */
    private fun builtInIcon(devicePackage: DevicePackage): ByteArray? {
        val dir = devicePackage.sourceName.substringBeforeLast('/', "definitions")
        val base = devicePackage.sourceName.substringAfterLast('/').substringBeforeLast('.')
        val path = "$dir/$base/assets/$ICON_NAME"
        return runCatching { assets.open(path).use { it.readBytes() } }.getOrNull()
    }

    /** Installed packages are ZIP archives; only the fixed `assets/icon.png` entry is read. */
    private fun installedIcon(packageId: String): ByteArray? {
        val file = File(packageDirectory, "$packageId.${DevicePackageStore.PACKAGE_EXTENSION}")
        if (!file.isFile) return null
        return runCatching {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == ICON_PATH) {
                        return@use zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                null
            }
        }.getOrNull()
    }

    private companion object {
        const val ICON_NAME = "icon.png"
        const val ICON_PATH = "assets/$ICON_NAME"
    }
}

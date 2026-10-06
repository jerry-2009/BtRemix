package com.Fusion.Btremix.device.registry

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.Fusion.Btremix.definition.packages.DevicePackage
import com.Fusion.Btremix.definition.packages.DevicePackageStore
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Supplies the device artwork shown on a device card (D-UI-5).
 *
 * The path is a fixed convention - `assets/icon.png` inside the package - and is deliberately not
 * declared in `manifest`, so no package can point the app at an arbitrary file or a network URL.
 * Returns `null` when the package has no icon; the UI then draws a monogram placeholder.
 *
 * [iconBytes] is the same fixed entry, raw, for the callers that are not Compose: the Melody bridge
 * hands the bytes to the host process so its detail / OneSpace header can show the package picture.
 */
interface DeviceArtworkProvider {
    fun artworkFor(packageId: String): ImageBitmap?

    /** Raw `assets/icon.png` of [packageId], or `null` when the package is unknown or has no icon. */
    fun iconBytes(packageId: String): ByteArray?
}

/** Android implementation: APK assets for built-ins, the `.dcpkg` zip for installed packages. */
class AndroidDeviceArtworkProvider(
    private val assets: AssetManager,
    private val packageDirectory: File,
    private val packages: () -> List<DevicePackage>,
) : DeviceArtworkProvider {

    private val bitmaps = HashMap<String, ImageBitmap?>()
    private val bytes = HashMap<String, ByteArray?>()

    override fun artworkFor(packageId: String): ImageBitmap? = synchronized(bitmaps) {
        if (bitmaps.containsKey(packageId)) return bitmaps[packageId]
        val bitmap = iconBytes(packageId)?.let { decode(it) }
        bitmaps[packageId] = bitmap
        bitmap
    }

    override fun iconBytes(packageId: String): ByteArray? = synchronized(bytes) {
        if (bytes.containsKey(packageId)) return bytes[packageId]
        val value = runCatching { loadBytes(packageId) }.getOrNull()
        bytes[packageId] = value
        value
    }

    fun invalidate() {
        synchronized(bitmaps) { bitmaps.clear() }
        synchronized(bytes) { bytes.clear() }
    }

    private fun loadBytes(packageId: String): ByteArray? {
        val devicePackage = packages().firstOrNull { it.packageId == packageId } ?: return null
        return if (devicePackage.isBuiltIn) builtInIcon(devicePackage) else installedIcon(packageId)
    }

    private fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

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

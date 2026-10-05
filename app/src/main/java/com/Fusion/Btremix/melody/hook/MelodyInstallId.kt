package com.Fusion.Btremix.melody.hook

import android.content.pm.ApplicationInfo
import java.io.File
import java.security.MessageDigest

/**
 * Host install fingerprint (M6).
 *
 * The host process needs a "which build is this?" key at `onPackageLoaded` time, before any `Context`
 * exists - and `ApplicationInfo` does *not* carry `versionName` (only `PackageInfo` does). The APK file
 * itself is available through `sourceDir`, so the fingerprint is the path + size + mtime of the base APK
 * and every split. Any Melody update replaces the APK, which changes the fingerprint and therefore
 * invalidates the persisted anchor report exactly once.
 */
internal object MelodyInstallId {

    private const val UNKNOWN = "unknown"
    private const val HEX_CHARS = 16

    fun of(info: ApplicationInfo?): String {
        if (info == null) return UNKNOWN
        val paths = buildList {
            info.sourceDir?.let { add(it) }
            info.splitSourceDirs?.forEach { add(it) }
        }
        if (paths.isEmpty()) return UNKNOWN
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            for (path in paths) {
                val file = File(path)
                digest.update("$path:${file.length()}:${file.lastModified()}".toByteArray())
            }
            digest.digest().joinToString("") { "%02x".format(it) }.take(HEX_CHARS)
        }.getOrDefault(UNKNOWN)
    }
}

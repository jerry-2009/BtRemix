package com.Fusion.Btremix.definition.packages

internal val PACKAGE_ID_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/**
 * Rejects absolute paths, Windows separators, drive letters and `..` traversal. Applied to every
 * ZIP entry name as well as to the `entry` field of `package.json`.
 */
internal fun requireSafePackagePath(name: String, sourceName: String? = null) {
    val unsafe = name.isEmpty() ||
        name.startsWith("/") ||
        name.contains('\\') ||
        name.contains('\u0000') ||
        name.contains(':') ||
        name.split('/').any { it == ".." || it == "." || it.isEmpty() }
    if (unsafe) {
        throw PackageException(
            PackageError.UnsafePath(
                entryName = name,
                detail = "unsafe entry path '$name'" + (sourceName?.let { " in '$it'" } ?: ""),
                path = name,
            ),
        )
    }
}

/** Files that would be executable content; never allowed inside a `.dcpkg`. */
private val FORBIDDEN_EXTENSIONS = listOf(
    ".dex", ".odex", ".vdex", ".so", ".class", ".jar", ".apk", ".aar",
    ".js", ".mjs", ".cjs", ".ts", ".py", ".sh", ".bat", ".cmd", ".exe", ".dll", ".elf",
)

/**
 * First version only allows JSON documents and resources under `assets/`. Everything else is
 * rejected as [PackageError.UnsupportedFormat].
 */
internal fun isAllowedPackageEntry(name: String): Boolean {
    val lower = name.lowercase()
    if (FORBIDDEN_EXTENSIONS.any { lower.endsWith(it) }) return false
    return lower.endsWith(".json") || lower.startsWith("assets/")
}

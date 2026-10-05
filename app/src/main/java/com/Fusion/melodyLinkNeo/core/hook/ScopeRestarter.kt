package com.fusion.melodyLinkNeo.core.hook

/**
 * "快速重启作用域" (quick restart scope): force-stops the LSPosed scopes this module declares so the
 * injected code is loaded again on the next host start.
 *
 * The scope list mirrors `app/src/main/resources/META-INF/xposed/scope.list`; keeping it here (instead
 * of reading the merged resource at runtime) means the product UI never has to open the APK. The
 * restart itself needs root, so a failure simply reports "no root" to the caller. Blocking work runs
 * off the main thread at the call site.
 */
object ScopeRestarter {

    /** Packages from `META-INF/xposed/scope.list`; keep in sync when the scope grows. */
    val DEFAULT_SCOPES: List<String> = listOf(
        "com.oplus.melody",
        "com.oplus.wirelesssettings",
    )

    private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_.]+$")

    /** Returns true when root force-stopped every requested package. Never throws. */
    fun restart(packages: Collection<String> = DEFAULT_SCOPES): Boolean {
        val targets = packages.map(String::trim).filter(PACKAGE_PATTERN::matches).distinct()
        if (targets.isEmpty()) return false
        return runCatching {
            val command = targets.joinToString("; ") { "am force-stop $it" }
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)
    }
}

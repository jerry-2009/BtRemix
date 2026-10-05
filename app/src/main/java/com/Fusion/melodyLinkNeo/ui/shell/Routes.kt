package com.fusion.melodyLinkNeo.ui.shell

/**
 * Every navigation route in one place (DEVICE_CENTER_UI_PLAN §6).
 *
 * Tab hosts are separate nested graphs so each tab keeps its own back stack; detail routes live
 * inside the owning tab's graph.
 */
object Routes {
    /** Value carried by `MelodyHostUpdateTracker.EXTRA_PAGE`; kept here so `ui` never imports `melody`. */
    const val MELODY_PAGE = "melody"

    const val HOME_GRAPH = "home"
    const val DEVICES_GRAPH = "devices"
    const val DEFINITIONS_GRAPH = "definitions"
    const val SETTINGS_GRAPH = "settings"

    const val HOME = "home/main"
    const val DEVICES = "devices/list"
    const val DEFINITIONS = "definitions/list"
    const val SETTINGS = "settings/main"
    const val LOGS = "settings/logs"
    const val ABOUT = "settings/about"
    const val DEVELOPER = "settings/developer"
    const val EXPLORER = "settings/developer/explorer"
    const val STUDIO = "settings/developer/studio"
    // Melody Dex 定位 moved out of 开发者 into 设置, so the route no longer sits under developer/.
    const val MELODY_DIAGNOSTICS = "settings/melody"

    const val DEVICE_SESSION_ARG = "mac"
    const val DEVICE_SESSION = "devices/session/{$DEVICE_SESSION_ARG}"
    fun deviceSession(mac: String): String = "devices/session/$mac"

    const val PACKAGE_DETAIL_ARG = "packageId"
    const val PACKAGE_DETAIL = "definitions/package/{$PACKAGE_DETAIL_ARG}"
    fun packageDetail(packageId: String): String = "definitions/package/$packageId"

    /**
     * Every concrete route registered in the shell's `NavHost`, parameterised routes included.
     * A JVM test asserts the bottom-nav tabs only ever point at entries of this set, because
     * navigating to a graph prefix such as `"home"` throws at runtime instead of failing to compile.
     */
    val registered: Set<String> = setOf(
        HOME,
        DEVICES,
        DEFINITIONS,
        SETTINGS,
        DEVICE_SESSION,
        PACKAGE_DETAIL,
        LOGS,
        ABOUT,
        DEVELOPER,
        EXPLORER,
        STUDIO,
        MELODY_DIAGNOSTICS,
    )

    /** Graph prefixes that are *not* destinations; kept for tab-ownership matching only. */
    val graphPrefixes: Set<String> = setOf(HOME_GRAPH, DEVICES_GRAPH, DEFINITIONS_GRAPH, SETTINGS_GRAPH)
}

package com.fusion.melodyLinkNeo.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The four first-level destinations (D-UI-1). Developer tools are a Settings sub-page.
 *
 * [route] must be a *registered* destination route, not a graph prefix: the shell uses one flat
 * `NavHost`, so navigating to `"home"` (the graph name) would throw
 * "destination that matches route home cannot be found". Tab ownership is still resolved by prefix
 * in [of], which is why the concrete routes keep the graph prefixes.
 */
enum class AppTab(val route: String, val label: String, val icon: ImageVector) {
    HOME(Routes.HOME, "首页", Icons.Rounded.Home),
    DEVICES(Routes.DEVICES, "设备", Icons.Rounded.BluetoothConnected),
    DEFINITIONS(Routes.DEFINITIONS, "定义", Icons.Rounded.DashboardCustomize),
    SETTINGS(Routes.SETTINGS, "设置", Icons.Rounded.Settings),
    ;

    companion object {
        /** Which tab owns [route]; null for routes that belong to no tab. */
        fun of(route: String?): AppTab? = when {
            route == null -> null
            route.startsWith(Routes.HOME_GRAPH) -> HOME
            route.startsWith(Routes.DEVICES_GRAPH) -> DEVICES
            route.startsWith(Routes.DEFINITIONS_GRAPH) -> DEFINITIONS
            route.startsWith(Routes.SETTINGS_GRAPH) -> SETTINGS
            else -> null
        }
    }
}

package com.Fusion.Btremix.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the crash "Navigation destination that matches route home cannot be found
 * in the navigation graph": the bottom bar navigated to a *graph prefix* (`home`) while the shell
 * registers flat destinations (`home/main`).
 */
class AppTabRoutesTest {

    @Test
    fun everyTabPointsAtARegisteredDestination() {
        AppTab.entries.forEach { tab ->
            assertTrue(
                "tab ${tab.name} route '${tab.route}' is not registered in the NavHost",
                tab.route in Routes.registered,
            )
        }
    }

    @Test
    fun noTabNavigatesToAGraphPrefix() {
        AppTab.entries.forEach { tab ->
            assertFalse(
                "tab ${tab.name} navigates to graph prefix '${tab.route}', which is not a destination",
                tab.route in Routes.graphPrefixes,
            )
        }
    }

    @Test
    fun tabOwnershipStillResolvesByPrefix() {
        AppTab.entries.forEach { tab ->
            assertTrue(
                "route '${tab.route}' does not resolve back to ${tab.name}",
                AppTab.of(tab.route) == tab,
            )
        }
        assertTrue(AppTab.of(Routes.deviceSession("AA:BB")) == AppTab.DEVICES)
        assertTrue(AppTab.of(Routes.packageDetail("demo.fusion")) == AppTab.DEFINITIONS)
        assertTrue(AppTab.of(Routes.MELODY_DIAGNOSTICS) == AppTab.SETTINGS)
    }
}

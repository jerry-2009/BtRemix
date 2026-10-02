package com.Fusion.Btremix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.Fusion.Btremix.ui.packages.DefinitionPackagesScreen
import com.Fusion.Btremix.ui.packages.PackageListItem
import com.Fusion.Btremix.ui.packages.PackageToolsUiState
import com.Fusion.Btremix.ui.theme.BtRemixTheme
import org.junit.Rule
import org.junit.Test

class DefinitionPackagesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsRegisteredPackages() {
        composeRule.setContent {
            BtRemixTheme {
                DefinitionPackagesScreen(
                    state = PackageToolsUiState(packages = listOf(samplePackage), loading = false),
                    onPackagePicked = {},
                    onReload = {},
                    onDelete = {},
                    onSelect = {},
                    onConfirmReplace = {},
                    onCancelReplace = {},
                    onDismissMessage = {},
                    onClearErrors = {},
                )
            }
        }

        composeRule.onNodeWithText("Definition packages").assertIsDisplayed()
        composeRule.onNodeWithText("Install package (.dcpkg)").assertIsDisplayed()
        composeRule.onNodeWithText("Vendor Device").assertIsDisplayed()
        composeRule.onNodeWithText("vendor.device").assertIsDisplayed()
    }

    @Test
    fun showsEmptyState() {
        composeRule.setContent {
            BtRemixTheme {
                DefinitionPackagesScreen(
                    state = PackageToolsUiState(loading = false),
                    onPackagePicked = {},
                    onReload = {},
                    onDelete = {},
                    onSelect = {},
                    onConfirmReplace = {},
                    onCancelReplace = {},
                    onDismissMessage = {},
                    onClearErrors = {},
                )
            }
        }

        composeRule.onNodeWithText("No packages registered").assertIsDisplayed()
    }

    private val samplePackage = PackageListItem(
        packageId = "vendor.device",
        displayName = "Vendor Device",
        version = "1.0.0",
        sourceName = "vendor-device.dcpkg",
        isBuiltIn = false,
        capabilities = listOf("battery"),
        matcherCount = 1,
        assetCount = 0,
    )
}

package com.Fusion.Btremix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.ui.packages.DefinitionPackagesScreen
import com.Fusion.Btremix.ui.packages.PackageListItem
import com.Fusion.Btremix.ui.packages.PackageToolsUiState
import com.Fusion.Btremix.ui.packages.PackageUiPreview
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
                    onPreview = {},
                    onDismissPreview = {},
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
                    onPreview = {},
                    onDismissPreview = {},
                    onConfirmReplace = {},
                    onCancelReplace = {},
                    onDismissMessage = {},
                    onClearErrors = {},
                )
            }
        }

        composeRule.onNodeWithText("No packages registered").assertIsDisplayed()
    }

    @Test
    fun previewDialogRendersTheConfiguredUi() {
        composeRule.setContent {
            BtRemixTheme {
                DefinitionPackagesScreen(
                    state = PackageToolsUiState(
                        packages = listOf(samplePackage),
                        loading = false,
                        preview = PackageUiPreview(
                            packageId = "cleer.arc3",
                            displayName = "Cleer ARC 3",
                            version = "1.0.0",
                            definition = DefinitionJsonCodec.decode(
                                """
                                {
                                  "manifest": {
                                    "id": "cleer.arc3",
                                    "displayName": "Cleer ARC 3",
                                    "version": "1.0.0",
                                    "matchers": [{ "type": "namePrefix", "value": "Cleer" }]
                                  },
                                  "states": {
                                    "volume": { "type": "integer", "displayName": "Volume", "default": 70, "min": 0, "max": 100, "step": 5 },
                                    "muted": { "type": "boolean", "displayName": "Mute", "default": false }
                                  },
                                  "actions": { "mute.set": { "displayName": "Set mute", "parameters": [{ "name": "value", "type": "boolean" }] } },
                                  "ui": {
                                    "title": "Cleer ARC 3",
                                    "children": [
                                      { "type": "section", "title": "Sound", "children": [
                                        { "type": "value", "state": "volume" },
                                        { "type": "switch", "state": "muted", "action": "mute.set" }
                                      ]}
                                    ]
                                  }
                                }
                                """.trimIndent(),
                            ),
                        ),
                    ),
                    onPackagePicked = {},
                    onReload = {},
                    onDelete = {},
                    onSelect = {},
                    onPreview = {},
                    onDismissPreview = {},
                    onConfirmReplace = {},
                    onCancelReplace = {},
                    onDismissMessage = {},
                    onClearErrors = {},
                )
            }
        }

        composeRule.onNodeWithText("UI preview").assertIsDisplayed()
        composeRule.onNodeWithText("Cleer ARC 3").assertIsDisplayed()
        composeRule.onNodeWithText("Sound").assertIsDisplayed()
        composeRule.onNodeWithText("Volume").assertIsDisplayed()
        composeRule.onNodeWithText("Mute").assertIsDisplayed()
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

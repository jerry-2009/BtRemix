package com.Fusion.Btremix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.Fusion.Btremix.ui.studio.DefinitionStudioScreen
import com.Fusion.Btremix.ui.studio.StudioUiState
import com.Fusion.Btremix.ui.theme.BtRemixTheme
import org.junit.Rule
import org.junit.Test

/** Milestone 5 UI wiring: the studio renders the editor, validation errors and drafts. */
class DefinitionStudioScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun studio_rendersEditorAndActions() {
        composeRule.setContent {
            BtRemixTheme {
                DefinitionStudioScreen(
                    state = StudioUiState(),
                    onEditorChange = {},
                    onValidate = {},
                    onSaveDraft = {},
                    onLoadDraft = {},
                    onDeleteDraft = {},
                    onToggleSimulation = {},
                    onAction = {},
                    onSelectTarget = {},
                    onNotificationHex = {},
                    onInject = {},
                    onExport = {},
                    onDismissMessage = {},
                )
            }
        }

        composeRule.onNodeWithText("Definition Studio").assertIsDisplayed()
        composeRule.onNodeWithText("Validate").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Simulate").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("No drafts yet").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun studio_surfacesValidationErrors() {
        composeRule.setContent {
            BtRemixTheme {
                DefinitionStudioScreen(
                    state = StudioUiState(errors = listOf("manifest.matchers: must contain at least one rule")),
                    onEditorChange = {},
                    onValidate = {},
                    onSaveDraft = {},
                    onLoadDraft = {},
                    onDeleteDraft = {},
                    onToggleSimulation = {},
                    onAction = {},
                    onSelectTarget = {},
                    onNotificationHex = {},
                    onInject = {},
                    onExport = {},
                    onDismissMessage = {},
                )
            }
        }

        composeRule.onNodeWithText("Validation errors").assertIsDisplayed()
        composeRule.onNodeWithText("manifest.matchers: must contain at least one rule").assertIsDisplayed()
    }
}

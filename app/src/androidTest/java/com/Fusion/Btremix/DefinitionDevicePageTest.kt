package com.Fusion.Btremix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristicProperty
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleService
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.StateEntry
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.ui.explorer.ConnectionDetails
import com.Fusion.Btremix.ui.explorer.DeviceDefinitionMatch
import com.Fusion.Btremix.ui.explorer.DeviceList
import com.Fusion.Btremix.ui.explorer.ExplorerView
import com.Fusion.Btremix.ui.explorer.ExplorerUiState
import com.Fusion.Btremix.ui.renderer.DefinitionDevicePage
import com.Fusion.Btremix.ui.theme.BtRemixTheme
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Milestone 2 UI wiring: definition page, raw GATT fallback and match badges. */
class DefinitionDevicePageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun definitionPage_rendersStateAndDispatchesAction() {
        var dispatched: DeviceAction? = null
        composeRule.setContent {
            BtRemixTheme {
                DefinitionDevicePage(
                    definition = definition,
                    state = mapOf("muted" to entry(StateValue.BooleanValue(true))),
                    onAction = { dispatched = it },
                )
            }
        }

        composeRule.onNodeWithText("Fusion Demo Device").assertIsDisplayed()
        composeRule.onNodeWithText("Mute").assertIsDisplayed()

        composeRule.onNode(isToggleable()).performClick()

        assertEquals(
            DeviceAction("mute.set", mapOf("value" to StateValue.BooleanValue(false))),
            dispatched,
        )
    }

    @Test
    fun deviceList_showsMatchBadgeAndUnrecognizedLabel() {
        val state = ExplorerUiState(
            devices = listOf(scanResult("AA:BB", "Fusion Buds"), scanResult("CC:DD", "Other")),
            matches = mapOf(
                "AA:BB" to DeviceDefinitionMatch("demo.fusion", "Fusion Demo Device", "1.0.1", 10, "name starts with \"Fusion\""),
            ),
        )
        composeRule.setContent { BtRemixTheme { DeviceList(state, onConnect = {}) } }

        composeRule.onNodeWithText("Matched Fusion Demo Device v1.0.1").assertIsDisplayed()
        composeRule.onNodeWithText("demo.fusion · priority 10 · name starts with \"Fusion\"").assertIsDisplayed()
        composeRule.onNodeWithText("Unrecognized device").assertIsDisplayed()
    }

    @Test
    fun connectionDetails_withoutDefinition_keepsRawGattTools() {
        val state = ExplorerUiState(
            connectedDevice = BleDevice("AA:BB", "Other"),
            services = listOf(service),
        )
        composeRule.setContent {
            BtRemixTheme {
                ConnectionDetails(
                    state = state,
                    onRead = {},
                    onWrite = { _, _, _ -> },
                    onToggleNotifications = {},
                    onAction = {},
                    onSelectView = {},
                    onClearActionError = {},
                )
            }
        }

        composeRule.onNodeWithText(CHARACTERISTIC_UUID.toString()).assertIsDisplayed()
        composeRule.onNodeWithText("Read").assertIsDisplayed()
        composeRule.onNodeWithText("Raw GATT").assertDoesNotExist()
    }

    @Test
    fun connectionDetails_withDefinition_offersRawGattTab() {
        var toggled = false
        val state = ExplorerUiState(
            connectedDevice = BleDevice("AA:BB", "Fusion Buds"),
            connectedDefinition = definition,
            definitionState = mapOf("muted" to entry(StateValue.BooleanValue(false))),
        )
        composeRule.setContent {
            BtRemixTheme {
                ConnectionDetails(
                    state = state,
                    onRead = {},
                    onWrite = { _, _, _ -> },
                    onToggleNotifications = {},
                    onAction = {},
                    onSelectView = { toggled = true },
                    onClearActionError = {},
                )
            }
        }

        composeRule.onNodeWithText("Definition demo.fusion v1.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("Raw GATT").assertIsDisplayed()
        composeRule.onNodeWithText("Raw GATT").performClick()

        assertEquals(true, toggled)
    }

    private fun entry(value: StateValue) = StateEntry(value, Instant.parse("2026-10-02T00:00:00Z"), StateSource.INITIAL)

    private fun scanResult(id: String, name: String) =
        com.Fusion.Btremix.core.bluetooth.api.BleScanResult(BleDevice(id, name), rssi = -40)

    private companion object {
        private val UUID_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val service = BleService(
            UUID_SERVICE,
            characteristics = listOf(
                BleCharacteristic(
                    serviceUuid = UUID_SERVICE,
                    uuid = CHARACTERISTIC_UUID,
                    properties = setOf(BleCharacteristicProperty.READ, BleCharacteristicProperty.WRITE),
                ),
            ),
        )

        private val definition: LoadedDeviceDefinition = DefinitionJsonCodec.decode(
            """
            {
              "manifest": {
                "id": "demo.fusion",
                "displayName": "Fusion Demo Device",
                "version": "1.0.0",
                "matchers": [{ "type": "namePrefix", "value": "Fusion" }]
              },
              "states": { "muted": { "type": "boolean", "displayName": "Mute", "default": false } },
              "actions": { "mute.set": { "displayName": "Set mute", "parameters": [{ "name": "value", "type": "boolean" }] } },
              "ui": {
                "title": "Fusion Demo Device",
                "children": [{ "type": "switch", "state": "muted", "action": "mute.set" }]
              }
            }
            """.trimIndent(),
        )
    }
}

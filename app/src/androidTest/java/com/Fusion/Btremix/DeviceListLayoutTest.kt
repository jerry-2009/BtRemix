package com.Fusion.Btremix

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.core.classic.api.ClassicDevice
import com.Fusion.Btremix.ui.explorer.DeviceList
import com.Fusion.Btremix.ui.explorer.ExplorerUiState
import com.Fusion.Btremix.ui.theme.BtRemixTheme
import org.junit.Rule
import org.junit.Test

/**
 * Regression for the Explorer list layout: the screen hosts [DeviceList] inside a bounded `Column`,
 * so anything it emits outside the scrolling container pushes the scan results off screen.
 */
class DeviceListLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scanResults_areVisibleEvenWhenManyPairedDevicesAreListed() {
        val state = ExplorerUiState(
            devices = listOf(BleScanResult(BleDevice("AA:BB:CC:DD:EE:01", "Test Buds"), rssi = -40)),
            classicDevices = (1..14).map { index ->
                ClassicDevice("0C:00:00:00:00:%02X".format(index), "Paired device $index")
            },
            permissionGranted = true,
        )
        composeRule.setContent {
            BtRemixTheme {
                // Same hosting contract as MainActivity: the list gets the remaining height.
                Column(Modifier.fillMaxSize()) {
                    DeviceList(state, onConnect = {}, modifier = Modifier.weight(1f))
                }
            }
        }

        composeRule.onNodeWithText("Test Buds").assertIsDisplayed()
    }

    @Test
    fun pairedDevices_areReachableByScrollingTheSameList() {
        val state = ExplorerUiState(
            devices = (1..6).map { index ->
                BleScanResult(BleDevice("AA:BB:CC:DD:EE:%02X".format(index), "Nearby $index"), rssi = -40 - index)
            },
            classicDevices = (1..14).map { index ->
                ClassicDevice("0C:00:00:00:00:%02X".format(index), "Paired device $index")
            },
            permissionGranted = true,
        )
        composeRule.setContent {
            BtRemixTheme {
                Column(Modifier.fillMaxSize()) {
                    DeviceList(state, onConnect = {}, modifier = Modifier.weight(1f))
                }
            }
        }

        // The whole screen must be one scrollable list, so paired devices stay reachable.
        composeRule.onNode(hasScrollAction()).assertExists()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Paired device 14"))

        composeRule.onNodeWithText("Paired device 14").assertIsDisplayed()
    }
}

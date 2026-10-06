package com.Fusion.Btremix.device.session

import com.Fusion.Btremix.core.classic.api.ClassicLinkEvent
import com.Fusion.Btremix.device.registry.DeviceConnectionState
import com.Fusion.Btremix.device.registry.DeviceDiscoveryKind
import com.Fusion.Btremix.device.registry.DeviceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HANDOFF_AUTO_SESSION.md §6 step 3 / §7: the pure rules behind the auto-session switch. */
class AutoSessionPolicyTest {
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val policy = AutoSessionPolicy()

    @Test
    fun risingEdgeOpensMatchedBondedClassicDevice() {
        val decision = policy.decide(
            enabled = true,
            event = ClassicLinkEvent(mac, "Demo Buds", connected = true),
            devices = listOf(entry(mac)),
            heldMacs = emptySet(),
            inFlightMacs = emptySet(),
        )

        assertEquals(AutoSessionDecision.Open(mac, "demo.fusion", "Demo Buds"), decision)
    }

    @Test
    fun duplicateRisingEdgeWhileHeldIsSkipped() {
        val decision = policy.decide(
            enabled = true,
            event = ClassicLinkEvent(mac, "Demo Buds", connected = true),
            devices = listOf(entry(mac)),
            heldMacs = setOf(mac),
            inFlightMacs = emptySet(),
        )

        assertEquals(AutoSessionDecision.Skip(mac, "already_held"), decision)
    }

    @Test
    fun duplicateRisingEdgeWhileInFlightIsSkipped() {
        val decision = policy.decide(
            enabled = true,
            event = ClassicLinkEvent(mac, "Demo Buds", connected = true),
            devices = listOf(entry(mac)),
            heldMacs = emptySet(),
            inFlightMacs = setOf(mac),
        )

        assertEquals(AutoSessionDecision.Skip(mac, "in_flight"), decision)
    }

    @Test
    fun fallingEdgeReleasesHeldDevice() {
        val decision = policy.decide(
            enabled = true,
            event = ClassicLinkEvent(mac, "Demo Buds", connected = false),
            devices = listOf(entry(mac)),
            heldMacs = setOf(mac),
            inFlightMacs = emptySet(),
        )

        assertEquals(AutoSessionDecision.Release(mac, "link_down"), decision)
    }

    @Test
    fun fallingEdgeForAnUnheldDeviceIsSkipped() {
        val decision = policy.decide(
            enabled = true,
            event = ClassicLinkEvent(mac, "Demo Buds", connected = false),
            devices = listOf(entry(mac)),
            heldMacs = emptySet(),
            inFlightMacs = emptySet(),
        )

        assertEquals(AutoSessionDecision.Skip(mac, "link_down_not_held"), decision)
    }

    @Test
    fun disabledNeverOpens() {
        val decision = policy.decide(
            enabled = false,
            event = ClassicLinkEvent(mac, "Demo Buds", connected = true),
            devices = listOf(entry(mac)),
            heldMacs = emptySet(),
            inFlightMacs = emptySet(),
        )

        assertEquals(AutoSessionDecision.Skip(mac, "disabled"), decision)
    }

    @Test
    fun disabledReportsEveryHeldMacForRelease() {
        assertEquals(listOf("AA:BB", mac), policy.onDisabled(setOf(mac, "AA:BB")))
        assertTrue(policy.onDisabled(emptySet()).isEmpty())
    }

    @Test
    fun bleScanMatchIsIgnored() {
        val decision = policy.decide(
            enabled = true,
            event = ClassicLinkEvent(mac, "Demo Buds", connected = true),
            devices = listOf(entry(mac, DeviceDiscoveryKind.BLE_SCAN)),
            heldMacs = emptySet(),
            inFlightMacs = emptySet(),
        )

        assertEquals(AutoSessionDecision.Skip(mac, "not_classic"), decision)
    }

    @Test
    fun unmatchedMacIsSkipped() {
        val decision = policy.decide(
            enabled = true,
            event = ClassicLinkEvent("11:22:33:44:55:66", "Unknown", connected = true),
            devices = listOf(entry(mac)),
            heldMacs = emptySet(),
            inFlightMacs = emptySet(),
        )

        assertEquals(AutoSessionDecision.Skip("11:22:33:44:55:66", "unmatched"), decision)
    }

    @Test
    fun addressNormalisationMatchesTheRegistry() {
        val decision = policy.decide(
            enabled = true,
            event = ClassicLinkEvent("  aa:bb:cc:dd:ee:ff  ", "Demo Buds", connected = true),
            devices = listOf(entry(mac)),
            heldMacs = emptySet(),
            inFlightMacs = emptySet(),
        )

        assertTrue(decision is AutoSessionDecision.Open)
        assertEquals(mac, decision.mac)
    }

    @Test
    fun retryBudgetIsTwoRetriesAfterTheFirstAttempt() {
        assertEquals(2_000L, policy.retryDelayMs(1))
        assertEquals(6_000L, policy.retryDelayMs(2))
        assertEquals(null, policy.retryDelayMs(3))
    }

    private fun entry(
        mac: String,
        discovery: DeviceDiscoveryKind = DeviceDiscoveryKind.BONDED_CLASSIC,
    ) = DeviceEntry(
        key = SessionRegistry.normalize(mac),
        mac = mac,
        name = "Demo Buds",
        packageId = "demo.fusion",
        packageDisplayName = "Demo",
        version = "1.0.0",
        author = "Fusion",
        deviceType = "headset",
        capabilities = emptyList(),
        discovery = discovery,
        rssi = null,
        state = DeviceConnectionState.DISCONNECTED,
        batteryPercent = null,
    )
}

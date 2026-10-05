package com.fusion.melodyLinkNeo.device.registry

import com.fusion.melodyLinkNeo.definition.api.DefinitionManifest
import com.fusion.melodyLinkNeo.definition.api.DeviceMatchRule
import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.definition.packages.DevicePackage
import com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState
import com.fusion.melodyLinkNeo.device.session.SessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.2 rules: only enabled packages match, an installed-but-disconnected device stays listed, and
 * a live session flips the card to 已连接.
 */
class DeviceRegistryTest {

    private class FakeDiscovery(initial: List<DiscoveredDevice>) : DeviceDiscoverySource {
        private val flow = MutableStateFlow(initial)
        override val devices: StateFlow<List<DiscoveredDevice>> = flow
        var refreshes = 0
        override fun refresh() { refreshes++ }
        fun emit(value: List<DiscoveredDevice>) { flow.value = value }
    }

    private fun pkg(id: String, namePrefix: String, enabledName: String = id) = DevicePackage(
        packageId = id,
        version = "1.0.0",
        sourceName = "$id.json",
        definition = LoadedDeviceDefinition(
            manifest = DefinitionManifest(
                id = id,
                displayName = enabledName,
                version = "1.0.0",
                matchers = listOf(DeviceMatchRule.NamePrefix(namePrefix)),
                capabilities = setOf("power.battery"),
                author = "Fusion",
                deviceType = "headset",
            ),
        ),
    )

    @Test
    fun onlyEnabledPackagesProduceEntries() = runBlocking {
        val packages = MutableStateFlow(listOf(pkg("demo.fusion", "Demo"), pkg("other.pkg", "Other")))
        val enabled = MutableStateFlow(setOf("demo.fusion"))
        val discovery = FakeDiscovery(listOf(DiscoveredDevice(mac = "AA:BB", name = "Demo Buds")))
        val sessions = MutableStateFlow<List<SessionSnapshot>>(emptyList())

        val registry = DeviceRegistry(packages, enabled, discovery, sessions, CoroutineScope(Dispatchers.Unconfined))
        val devices = withTimeout(5_000) { registry.devices.first { it.isNotEmpty() } }

        assertEquals(1, devices.size)
        assertEquals("demo.fusion", devices.first().packageId)
        assertEquals(DeviceConnectionState.DISCONNECTED, devices.first().state)
        assertEquals("Fusion", devices.first().author)
    }

    @Test
    fun disabledPackageRemovesItsDevices() = runBlocking {
        val packages = MutableStateFlow(listOf(pkg("demo.fusion", "Demo")))
        val enabled = MutableStateFlow(setOf("demo.fusion"))
        val discovery = FakeDiscovery(listOf(DiscoveredDevice(mac = "AA:BB", name = "Demo Buds")))
        val sessions = MutableStateFlow<List<SessionSnapshot>>(emptyList())
        val registry = DeviceRegistry(packages, enabled, discovery, sessions, CoroutineScope(Dispatchers.Unconfined))

        withTimeout(5_000) { registry.devices.first { it.isNotEmpty() } }
        enabled.value = emptySet()
        val after = withTimeout(5_000) { registry.devices.first { it.isEmpty() } }
        assertTrue(after.isEmpty())
    }

    @Test
    fun liveSessionMarksDeviceConnected() = runBlocking {
        val packages = MutableStateFlow(listOf(pkg("demo.fusion", "Demo")))
        val enabled = MutableStateFlow(setOf("demo.fusion"))
        val discovery = FakeDiscovery(listOf(DiscoveredDevice(mac = "AA:BB", name = "Demo Buds")))
        val sessions = MutableStateFlow<List<SessionSnapshot>>(emptyList())
        val registry = DeviceRegistry(
            packages,
            enabled,
            discovery,
            sessions,
            CoroutineScope(Dispatchers.Unconfined),
            batteryOf = { _, _ -> 88 },
        )

        withTimeout(5_000) { registry.devices.first { it.isNotEmpty() } }
        sessions.value = listOf(
            SessionSnapshot(mac = "AA:BB", lifecycle = DeviceLifecycleState.Ready, state = emptyMap()),
        )
        val connected = withTimeout(5_000) { registry.devices.first { it.firstOrNull()?.connected == true } }

        assertEquals(88, connected.first().batteryPercent)
        assertEquals(DeviceConnectionState.CONNECTED, connected.first().state)
    }

    @Test
    fun unmatchedDiscoveredDeviceIsNotListed() = runBlocking {
        val packages = MutableStateFlow(listOf(pkg("demo.fusion", "Demo")))
        val enabled = MutableStateFlow(setOf("demo.fusion"))
        val discovery = FakeDiscovery(listOf(DiscoveredDevice(mac = "CC:DD", name = "Something Else")))
        val sessions = MutableStateFlow<List<SessionSnapshot>>(emptyList())
        val registry = DeviceRegistry(packages, enabled, discovery, sessions, CoroutineScope(Dispatchers.Unconfined))

        val devices = withTimeout(5_000) { registry.devices.first { true } }
        // The flow is eagerly started, so the first emission already reflects the (empty) match set.
        assertTrue(devices.isEmpty())

        // And it must never gain an entry just because the package list re-emits.
        packages.value = packages.value.toList()
        assertNull(registry.devices.value.firstOrNull())
    }
}

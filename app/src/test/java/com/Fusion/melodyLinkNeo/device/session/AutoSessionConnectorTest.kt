package com.fusion.melodyLinkNeo.device.session

import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.core.classic.api.ClassicConnectionMonitor
import com.fusion.melodyLinkNeo.core.classic.api.ClassicDevice
import com.fusion.melodyLinkNeo.core.classic.api.ClassicLinkEvent
import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.definition.packages.DevicePackageRegistry
import com.fusion.melodyLinkNeo.device.registry.DeviceConnectionState
import com.fusion.melodyLinkNeo.device.registry.DeviceDiscoveryKind
import com.fusion.melodyLinkNeo.device.registry.DeviceEntry
import com.fusion.melodyLinkNeo.device.runtime.DefaultDeviceRuntime
import com.fusion.melodyLinkNeo.device.runtime.FakeBleConnection
import com.fusion.melodyLinkNeo.device.runtime.ProtocolSession
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HANDOFF_AUTO_SESSION.md §6 step 3 / §7: the connector turns link events into exactly one owned
 * session per device and releases it again, without ever stealing a session another front-end holds.
 */
@OptIn(FlowPreview::class)
class AutoSessionConnectorTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC)
    private val mac = "AA:BB:CC:DD:EE:FF"

    private class FakeMonitor : ClassicConnectionMonitor {
        private val flow = MutableSharedFlow<ClassicLinkEvent>(extraBufferCapacity = 16)
        override val events: Flow<ClassicLinkEvent> = flow.asSharedFlow()
        private val offFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        override val adapterOff: Flow<Unit> = offFlow.asSharedFlow()
        var connected: List<ClassicDevice> = emptyList()

        override suspend fun connectedDevices(): List<ClassicDevice> = connected
        override fun start(scope: CoroutineScope) = Unit
        override fun stop() = Unit

        suspend fun emit(event: ClassicLinkEvent) = flow.emit(event)
        suspend fun emitAdapterOff() = offFlow.emit(Unit)
        suspend fun awaitSubscriber() = withTimeout(2_000) { flow.subscriptionCount.first { it > 0 } }
    }

    @Test
    fun connectOpensOnceAndDisconnectReleases() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val monitor = FakeMonitor()
        var opened = 0
        val connector = connector(monitor, manager, open = { _, _, _ -> opened++; openSession() }, scope = scope)
        connector.start()

        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        awaitRefCount(registry, mac, 1)
        assertEquals(1, opened)
        assertEquals(setOf(mac), manager.heldMacs(HoldReason.AUTO_CONNECT))

        // Our own SPP connection lifts the ACL again; a duplicate edge must not open a second session.
        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        delay(20)
        assertEquals(1, opened)
        assertEquals(1, registry.refCount(mac))

        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = false))
        awaitRefCount(registry, mac, 0)
        assertNull(registry.find(mac))
        assertTrue(manager.heldMacs(HoldReason.AUTO_CONNECT).isEmpty())

        // A new link cycle is a new rising edge and opens again.
        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        awaitRefCount(registry, mac, 1)
        assertEquals(2, opened)

        connector.stop()
        scope.cancel()
    }

    @Test
    fun autoHoldNeverClosesASessionAnotherFrontEndOwns() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val monitor = FakeMonitor()
        // Simulate the Compose UI or the Melody bridge having opened the session first.
        registry.register(mac, openSession())
        var opened = 0
        val connector = connector(monitor, manager, open = { _, _, _ -> opened++; openSession() }, scope = scope)
        connector.start()

        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        // The auto hold adopts the existing session (no second open) and adds one reference.
        awaitRefCount(registry, mac, 2)
        assertEquals(0, opened)

        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = false))
        awaitRefCount(registry, mac, 1)
        assertNotNull(registry.find(mac))

        registry.closeAll()
        connector.stop()
        scope.cancel()
    }

    @Test
    fun disablingReleasesHoldsAndBlocksNewOpens() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val monitor = FakeMonitor()
        val enabled = MutableStateFlow(true)
        var opened = 0
        val connector = connector(
            monitor,
            manager,
            open = { _, _, _ -> opened++; openSession() },
            scope = scope,
            enabled = enabled,
        )
        connector.start()
        monitor.awaitSubscriber()

        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        awaitRefCount(registry, mac, 1)

        enabled.value = false
        awaitRefCount(registry, mac, 0)
        assertNull(registry.find(mac))

        // Still connected, but the switch is off: a fresh edge must not open anything.
        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        delay(20)
        assertEquals(1, opened)
        assertEquals(0, registry.refCount(mac))

        connector.stop()
        scope.cancel()
    }

    @Test
    fun failedOpenRetriesTwiceThenGivesUpOnTheSameEdge() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val monitor = FakeMonitor()
        val delays = mutableListOf<Long>()
        var attempts = 0
        val connector = connector(
            monitor,
            manager,
            open = { _, _, _ -> attempts++; error("boom") },
            scope = scope,
            retryDelay = { delays += it },
        )
        connector.start()

        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        withTimeout(2_000) { while (attempts < 3) delay(5) }

        assertEquals(listOf(2_000L, 6_000L), delays)
        assertEquals(0, registry.refCount(mac))
        assertTrue(manager.heldMacs(HoldReason.AUTO_CONNECT).isEmpty())

        connector.stop()
        scope.cancel()
    }

    @Test
    fun seedOpensForAnAlreadyConnectedDevice() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val monitor = FakeMonitor()
        var opened = 0
        val connector = connector(monitor, manager, open = { _, _, _ -> opened++; openSession() }, scope = scope)
        connector.start()

        connector.seed(listOf(ClassicDevice(address = mac, name = "Demo Buds")))

        awaitRefCount(registry, mac, 1)
        assertEquals(1, opened)

        connector.stop()
        scope.cancel()
    }

    @Test
    fun opensOnceTheDeviceRegistryPopulatesAfterTheLinkEdge() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val monitor = FakeMonitor()
        // Discovery has not produced the bonded-device list yet, so the rising edge cannot match.
        val devices = MutableStateFlow(emptyList<DeviceEntry>())
        var opened = 0
        val connector = connector(
            monitor,
            manager,
            open = { _, _, _ -> opened++; openSession() },
            scope = scope,
            devices = devices,
        )
        connector.start()

        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        delay(20)
        assertEquals(0, opened)

        // The package match arrives late; the already-connected link must still open.
        devices.value = listOf(entry(mac))
        awaitRefCount(registry, mac, 1)
        assertEquals(1, opened)

        connector.stop()
        scope.cancel()
    }

    @Test
    fun staleHoldIsReleasedAndTheLinkReopens() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val monitor = FakeMonitor()
        val events = mutableListOf<AutoSessionEvent>()
        var opened = 0
        val connector = connector(
            monitor,
            manager,
            open = { _, _, _ -> opened++; openSession() },
            scope = scope,
            onEvent = { events += it },
        )
        connector.start()

        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        awaitRefCount(registry, mac, 1)
        assertEquals(1, opened)

        // System Bluetooth switched off: the socket dies but no ACTION_ACL_DISCONNECTED reached us,
        // so the AUTO_CONNECT hold is left behind pointing at a dead session.
        registry.find(mac)!!.close()
        assertFalse(manager.isLive(mac))
        assertTrue(manager.isHeld(mac, HoldReason.AUTO_CONNECT))

        // Adapter is back and the headset reconnects: the stale hold must not block the rebuild.
        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        awaitRefCount(registry, mac, 1)
        assertEquals(2, opened)
        assertTrue(manager.isLive(mac))
        assertTrue(events.any { it is AutoSessionEvent.Released && it.reason == "stale_session" })

        connector.stop()
        scope.cancel()
    }

    @Test
    fun seedDoesNotDuplicateAHealthyHold() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val monitor = FakeMonitor()
        var opened = 0
        val connector = connector(monitor, manager, open = { _, _, _ -> opened++; openSession() }, scope = scope)
        connector.start()

        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        awaitRefCount(registry, mac, 1)

        // The periodic catch-up must leave a healthy hold alone.
        connector.seed(listOf(ClassicDevice(address = mac, name = "Demo Buds")))
        delay(20)
        assertEquals(1, opened)
        assertEquals(1, registry.refCount(mac))

        connector.stop()
        scope.cancel()
    }

    @Test
    fun adapterOffReleasesEveryAutoHold() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val monitor = FakeMonitor()
        var opened = 0
        val connector = connector(monitor, manager, open = { _, _, _ -> opened++; openSession() }, scope = scope)
        connector.start()

        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        awaitRefCount(registry, mac, 1)

        // Whole-adapter power off: no per-device disconnect broadcast is guaranteed.
        monitor.emitAdapterOff()
        awaitRefCount(registry, mac, 0)
        assertNull(registry.find(mac))
        assertTrue(manager.heldMacs(HoldReason.AUTO_CONNECT).isEmpty())
        assertEquals(1, opened)

        connector.stop()
        scope.cancel()
    }

    @Test
    fun aMidOpenHoldIsNotTreatedAsStale() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Default)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val monitor = FakeMonitor()
        val gate = CompletableDeferred<Unit>()
        val connector = connector(
            monitor,
            manager,
            open = { _, _, _ -> gate.await(); openSession() },
            scope = scope,
        )
        connector.start()

        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        withTimeout(2_000) { while (!manager.isHeld(mac, HoldReason.AUTO_CONNECT)) delay(5) }

        // The registry slot is not published until `open` returns, so a second edge saw an
        // "unheld" MAC; it must not be read as a stale hold and dropped mid-open.
        monitor.emit(ClassicLinkEvent(mac, "Demo Buds", connected = true))
        delay(50)
        assertTrue(manager.isHeld(mac, HoldReason.AUTO_CONNECT))
        assertEquals(0, registry.refCount(mac))

        gate.complete(Unit)
        awaitRefCount(registry, mac, 1)
        assertTrue(manager.isLive(mac))

        connector.stop()
        scope.cancel()
    }

    private fun connector(
        monitor: FakeMonitor,
        manager: SessionManager,
        open: suspend (String, String?, LoadedDeviceDefinition?) -> ProtocolSession,
        scope: CoroutineScope,
        enabled: MutableStateFlow<Boolean> = MutableStateFlow(true),
        retryDelay: suspend (Long) -> Unit = {},
        devices: MutableStateFlow<List<DeviceEntry>> = MutableStateFlow(listOf(entry(mac))),
        onEvent: (AutoSessionEvent) -> Unit = {},
    ) = AutoSessionConnector(
        monitor = monitor,
        devices = devices,
        sessions = manager,
        packageRegistry = DevicePackageRegistry(),
        enabled = enabled,
        open = open,
        scope = scope,
        retryDelay = retryDelay,
        onEvent = onEvent,
    )

    private suspend fun awaitRefCount(registry: SessionRegistry, mac: String, expected: Int) {
        withTimeout(2_000) {
            while (registry.refCount(mac) != expected) delay(5)
        }
    }

    private suspend fun openSession(): ProtocolSession =
        DefaultDeviceRuntime(clock).open(BleDevice(mac, "Fusion"), FakeBleConnection())

    private fun entry(mac: String) = DeviceEntry(
        key = SessionRegistry.normalize(mac),
        mac = mac,
        name = "Demo Buds",
        packageId = "demo.fusion",
        packageDisplayName = "Demo",
        version = "1.0.0",
        author = "Fusion",
        deviceType = "headset",
        capabilities = emptyList(),
        discovery = DeviceDiscoveryKind.BONDED_CLASSIC,
        rssi = null,
        state = DeviceConnectionState.DISCONNECTED,
        batteryPercent = null,
    )
}

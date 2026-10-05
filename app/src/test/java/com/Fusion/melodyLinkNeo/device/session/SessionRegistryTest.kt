package com.fusion.melodyLinkNeo.device.session

import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.device.runtime.DefaultDeviceRuntime
import com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState
import com.fusion.melodyLinkNeo.device.runtime.FakeBleConnection
import com.fusion.melodyLinkNeo.device.runtime.StateSource
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0 skeleton coverage for the process-scoped session registry (MELODY_BRIDGE_SPEC §3.3).
 *
 * The registry is what stops the Melody bridge and the Compose UI from opening two sessions for the
 * same headset, so its key normalisation and single-owner semantics are pinned down here before the
 * bridge starts using it in M2.
 */
class SessionRegistryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC)
    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun lookup_isCaseAndWhitespaceInsensitive() = runBlocking {
        val session = openSession()
        val registry = SessionRegistry()
        registry.register("aa:bb:cc:dd:ee:ff", session)

        assertSame(session, registry.find(mac))
        assertSame(session, registry.find("  aa:bb:cc:dd:ee:ff  "))
        assertEquals(listOf(mac), registry.managedMacs())

        registry.closeAll()
    }

    @Test
    fun register_returnsPreviousOwnerForTheSameMac() = runBlocking {
        val first = openSession()
        val second = openSession()
        val registry = SessionRegistry()

        assertNull(registry.register(mac, first))
        assertSame(first, registry.register(mac, second))
        assertSame(second, registry.find(mac))
        assertEquals(1, registry.managedMacs().size)

        registry.closeAll()
    }

    @Test
    fun close_detachesAndDisconnectsTheSession() = runBlocking {
        val session = openSession()
        val registry = SessionRegistry()
        registry.register(mac, session)

        registry.close(mac)

        assertNull(registry.find(mac))
        assertEquals(DeviceLifecycleState.Disconnected, session.lifecycle.value)
    }

    @Test
    fun closeAll_emptiesTheRegistryAndDisconnectsEverySession() = runBlocking {
        val registry = SessionRegistry()
        val first = openSession()
        val second = openSession()
        registry.register("AA:BB:CC:DD:EE:01", first)
        registry.register("AA:BB:CC:DD:EE:02", second)

        registry.closeAll()

        assertTrue(registry.managedMacs().isEmpty())
        assertEquals(DeviceLifecycleState.Disconnected, first.lifecycle.value)
        assertEquals(DeviceLifecycleState.Disconnected, second.lifecycle.value)
    }

    /**
     * M2a ownership contract (§12): the registry is the sole creator. Two acquires for the same MAC
     * yield one session, and one release never disturbs the other holder.
     */
    @Test
    fun acquire_reusesOneSessionAndCountsReferences() = runBlocking {
        val registry = SessionRegistry()
        val opened = AtomicInteger()

        val first = registry.acquire(mac) {
            opened.incrementAndGet()
            openSession()
        }
        val second = registry.acquire("  aa:bb:cc:dd:ee:ff  ") {
            opened.incrementAndGet()
            openSession()
        }

        assertSame(first, second)
        assertEquals(1, opened.get())
        assertEquals(2, registry.refCount(mac))

        registry.release(mac)
        assertSame(first, registry.find(mac))
        assertEquals(1, registry.refCount(mac))
        assertEquals(DeviceLifecycleState.Ready, first.lifecycle.value)

        registry.release(mac)
        assertNull(registry.find(mac))
        assertEquals(0, registry.refCount(mac))
        assertEquals(DeviceLifecycleState.Disconnected, first.lifecycle.value)
    }

    /** Concurrent acquires for the same MAC must not open two connections. */
    @Test
    fun acquire_singleFlightsConcurrentOpens() = runBlocking {
        val registry = SessionRegistry()
        val opened = AtomicInteger()

        val results = coroutineScope {
            (1..8).map {
                async(Dispatchers.Default) {
                    registry.acquire(mac) {
                        opened.incrementAndGet()
                        delay(50)
                        openSession()
                    }
                }
            }.awaitAll()
        }

        assertTrue(results.all { it === results.first() })
        assertEquals(1, opened.get())
        assertEquals(8, registry.refCount(mac))

        repeat(8) { registry.release(mac) }
        assertNull(registry.find(mac))
    }

    /** Releasing an unknown MAC is a no-op so a raced disconnect cannot throw. */
    @Test
    fun release_isNoOpForUnknownMac() = runBlocking {
        val registry = SessionRegistry()
        registry.release(mac)
        assertEquals(0, registry.refCount(mac))
    }

    /** A fresh acquire after the last release opens a brand new session. */
    @Test
    fun acquire_reopensAfterTheLastRelease() = runBlocking {
        val registry = SessionRegistry()
        val first = registry.acquire(mac) { openSession() }
        registry.release(mac)

        val second = registry.acquire(mac) { openSession() }

        assertTrue(first !== second)
        registry.closeAll()
    }

    /** Snapshot is a detached copy, so mutating live data never reaches the snapshot. */
    @Test
    fun snapshot_detachesLifecycleAndState() = runBlocking {
        val registry = SessionRegistry()
        val registrySession = openSession()
        registry.register(mac, registrySession)
        val payload = byteArrayOf(1, 2, 3)
        registrySession.state.set("battery.left", payload, StateSource.NOTIFICATION)

        val snapshot = registry.snapshot("  aa:bb:cc:dd:ee:ff  ")!!
        payload[0] = 9

        assertEquals(mac, snapshot.mac)
        assertEquals(DeviceLifecycleState.Ready, snapshot.lifecycle)
        val value = snapshot.state.getValue("battery.left").value
        assertTrue(value is StateValue.BytesValue)
        assertTrue((value as StateValue.BytesValue).value.contentEquals(byteArrayOf(1, 2, 3)))

        registry.closeAll()
    }

    @Test
    fun snapshot_isNullForUnmanagedMac() {
        assertNull(SessionRegistry().snapshot(mac))
    }

    /**
     * M2b: the Melody bridge attaches one state watcher per managed MAC, so it needs the ownership
     * transitions as an observable list instead of polling [SessionRegistry.managedMacs].
     */
    @Test
    fun managedMacsFlow_tracksOwnershipTransitionsNormalised() = runBlocking {
        val registry = SessionRegistry()
        assertTrue(registry.managedMacsFlow.value.isEmpty())

        val session = registry.acquire("  aa:bb:cc:dd:ee:ff  ") { openSession() }
        assertEquals(listOf(mac), registry.managedMacsFlow.value)

        registry.release(mac)
        assertTrue(registry.managedMacsFlow.value.isEmpty())

        registry.register("aa:bb:cc:dd:ee:ff", session)
        assertEquals(listOf(mac), registry.managedMacsFlow.value)

        registry.close(mac)
        assertTrue(registry.managedMacsFlow.value.isEmpty())
    }

    @Test
    fun managedMacsFlow_closeAllClearsEveryEntry() = runBlocking {
        val registry = SessionRegistry()
        registry.register("AA:BB:CC:DD:EE:01", openSession())
        registry.register("AA:BB:CC:DD:EE:02", openSession())
        assertEquals(2, registry.managedMacsFlow.value.size)

        registry.closeAll()

        assertTrue(registry.managedMacsFlow.value.isEmpty())
    }

    private suspend fun openSession() =
        DefaultDeviceRuntime(clock).open(BleDevice(mac, "Fusion"), FakeBleConnection())
}

package com.Fusion.Btremix.device.session

import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.device.runtime.FakeBleConnection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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

    private suspend fun openSession() =
        DefaultDeviceRuntime(clock).open(BleDevice(mac, "Fusion"), FakeBleConnection())
}

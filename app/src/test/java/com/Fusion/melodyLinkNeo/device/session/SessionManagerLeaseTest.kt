package com.fusion.melodyLinkNeo.device.session

import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.device.runtime.DefaultDeviceRuntime
import com.fusion.melodyLinkNeo.device.runtime.FakeBleConnection
import com.fusion.melodyLinkNeo.device.runtime.ProtocolSession
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HANDOFF_AUTO_SESSION.md §6 step 2 / §7: app-scoped holds are keyed by `(mac, reason)` so releasing
 * one feature never tears down a session the other one still owns.
 */
class SessionManagerLeaseTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC)
    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun releasingBackgroundRunKeepsAutoConnectHold() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val session = openSession()
        registry.register(mac, session)

        manager.hold(mac, HoldReason.BACKGROUND_RUN)
        manager.hold(mac, HoldReason.AUTO_CONNECT)
        awaitRefCount(registry, mac, 3)

        manager.releaseHolds(HoldReason.BACKGROUND_RUN)
        awaitRefCount(registry, mac, 2)
        assertSame(session, registry.find(mac))
        assertTrue(manager.heldMacs(HoldReason.BACKGROUND_RUN).isEmpty())
        assertEquals(setOf(mac), manager.heldMacs(HoldReason.AUTO_CONNECT))

        manager.releaseHolds(HoldReason.AUTO_CONNECT)
        awaitRefCount(registry, mac, 1)
        // The reference the caller registered with is untouched - auto release never steals it.
        assertSame(session, registry.find(mac))

        registry.closeAll()
        scope.cancel()
    }

    @Test
    fun holdOrOpenOpensOnceAndIsIdempotentPerReason() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        var opened = 0

        assertTrue(manager.holdOrOpen(mac, HoldReason.AUTO_CONNECT) { opened++; openSession() })
        assertEquals(1, opened)
        assertEquals(1, registry.refCount(mac))
        assertTrue(manager.isHeld(mac, HoldReason.AUTO_CONNECT))

        // A second hold for the same reason must not open a session or bump the reference count.
        assertTrue(manager.holdOrOpen(mac, HoldReason.AUTO_CONNECT) { opened++; openSession() })
        assertEquals(1, opened)
        assertEquals(1, registry.refCount(mac))

        // A different reason is a distinct owner.
        manager.hold(mac, HoldReason.BACKGROUND_RUN)
        awaitRefCount(registry, mac, 2)

        manager.releaseHolds(HoldReason.AUTO_CONNECT)
        awaitRefCount(registry, mac, 1)
        manager.releaseHolds(HoldReason.BACKGROUND_RUN)
        awaitRefCount(registry, mac, 0)
        assertNull(registry.find(mac))

        scope.cancel()
    }

    @Test
    fun failedHoldOrOpenDoesNotRecordAHold() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)

        val opened = manager.holdOrOpen(mac, HoldReason.AUTO_CONNECT) { error("boom") }

        assertTrue(!opened)
        assertTrue(manager.heldMacs(HoldReason.AUTO_CONNECT).isEmpty())
        assertEquals(0, registry.refCount(mac))
        scope.cancel()
    }

    @Test
    fun releaseForAnUnknownReasonIsANoOp() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        registry.register(mac, openSession())

        manager.releaseHolds(HoldReason.AUTO_CONNECT)
        manager.releaseHold(mac, HoldReason.BACKGROUND_RUN)

        assertEquals(1, registry.refCount(mac))
        registry.closeAll()
        scope.cancel()
    }

    @Test
    fun isLiveFollowsTheSessionLifecycle() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val session = openSession()
        registry.register(mac, session)

        assertTrue(manager.isLive(mac))

        // A dead socket leaves the registry slot (and any hold) in place: that is exactly the state
        // the connector has to detect and clean up.
        session.close()
        assertFalse(manager.isLive(mac))

        registry.closeAll()
        scope.cancel()
    }

    /**
     * A leaked reference elsewhere can keep a dead registry slot alive after the auto hold is
     * released; the reconnect must replace that slot instead of adopting it (which would report
     * "opened" while the device stays disconnected).
     */
    @Test
    fun holdOrOpenReplacesADeadSessionInsteadOfAdoptingIt() = runBlocking {
        val registry = SessionRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SessionManager(registry, scope, pollIntervalMs = 60_000)
        val dead = openSession()
        registry.register(mac, dead)
        dead.close()
        assertFalse(manager.isLive(mac))

        var opened = 0
        val ok = manager.holdOrOpen(mac, HoldReason.AUTO_CONNECT) { opened++; openSession() }

        assertTrue(ok)
        assertEquals(1, opened)
        assertTrue(manager.isLive(mac))
        assertEquals(1, registry.refCount(mac))

        registry.closeAll()
        scope.cancel()
    }

    private suspend fun awaitRefCount(registry: SessionRegistry, mac: String, expected: Int) {
        withTimeout(2_000) {
            while (registry.refCount(mac) != expected) delay(5)
        }
    }

    private suspend fun openSession(): ProtocolSession =
        DefaultDeviceRuntime(clock).open(BleDevice(mac, "Fusion"), FakeBleConnection())
}

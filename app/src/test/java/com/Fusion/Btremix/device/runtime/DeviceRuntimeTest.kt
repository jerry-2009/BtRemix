package com.Fusion.Btremix.device.runtime

import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleConnection
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleService
import com.Fusion.Btremix.core.bluetooth.api.ConnectionState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRuntimeTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
    private val device = BleDevice("test-device", "Fake")

    @Test
    fun stateStore_emitsEntryWithMetadataAndSupportsTypedValues() = runBlocking {
        val store = InMemoryStateStore(fixedClock)
        store.set("battery", StateValue.IntValue(83), StateSource.NOTIFICATION, StateQuality.FRESH)

        val entry = store.get("battery")
        assertEquals(StateValue.IntValue(83), entry?.value)
        assertEquals(StateSource.NOTIFICATION, entry?.source)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), entry?.timestamp)
        assertEquals(StateValue.IntValue(83), store.entries.value["battery"]?.value)
    }

    @Test
    fun runtime_open_discoversServicesAndReachesReady() = runBlocking {
        val connection = FakeBleConnection()
        val session = DefaultDeviceRuntime(fixedClock).open(device, connection)

        assertEquals(DeviceLifecycleState.Ready, session.lifecycle.value)
        assertEquals(1, session.services.value.size)
        assertEquals(1, connection.discoverCount)

        session.close()
        assertEquals(DeviceLifecycleState.Disconnected, session.lifecycle.value)
        assertTrue(connection.disconnected)
    }

    @Test
    fun action_dispatch_returnsResultAndPublishesCompletion() = runBlocking {
        val session = DefaultDeviceRuntime(fixedClock).open(device, FakeBleConnection())
        session.registerAction("volume.set") { action ->
            ActionResult.Success(action.args.getValue("value"))
        }

        val result = session.execute(
            DeviceAction("volume.set", mapOf("value" to StateValue.IntValue(60))),
        )

        assertEquals(ActionResult.Success(StateValue.IntValue(60)), result)
        session.close()
    }

    @Test
    fun unknownAction_returnsStructuredFailure() = runBlocking {
        val session = DefaultDeviceRuntime(fixedClock).open(device, FakeBleConnection())

        val result = session.execute(DeviceAction("missing"))

        assertEquals(ActionResult.Failure(RuntimeError.ActionNotFound("missing")), result)
        session.close()
    }

    private class FakeBleConnection : BleConnection {
        private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        var discoverCount = 0
        var disconnected = false

        override val state = mutableState

        override suspend fun discoverServices(): List<BleService> {
            discoverCount++
            return listOf(BleService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")))
        }

        override suspend fun read(characteristic: BleCharacteristic): ByteArray = byteArrayOf()

        override suspend fun write(characteristic: BleCharacteristic, data: ByteArray, withResponse: Boolean) = Unit

        override fun notifications(characteristic: BleCharacteristic): Flow<ByteArray> = emptyFlow()

        override suspend fun disconnect() {
            disconnected = true
            mutableState.value = ConnectionState.Disconnected
        }
    }
}

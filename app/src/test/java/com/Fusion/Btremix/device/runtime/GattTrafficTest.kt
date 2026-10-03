package com.Fusion.Btremix.device.runtime

import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 4: the session emits every GATT read/write/notification so the monitor can record it. */
class GattTrafficTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC)
    private val device = BleDevice("AA:BB", "Fusion")

    @Test
    fun readAndWrite_arePublishedAsEvents() = runBlocking {
        val connection = FakeBleConnection(readValue = byteArrayOf(0x0A))
        val session = DefaultDeviceRuntime(clock).open(device, connection)
        val characteristic = connection.discoverServices().first().let { service ->
            BleCharacteristic(service.uuid, FakeBleConnection.DEFAULT_CHARACTERISTIC_UUID)
        }

        val read = async(start = CoroutineStart.UNDISPATCHED) { session.events.first { it is DeviceEvent.GattRead } }
        session.read(characteristic)
        val readEvent = read.await() as DeviceEvent.GattRead
        assertArrayEquals(byteArrayOf(0x0A), readEvent.data)
        assertEquals(characteristic, readEvent.characteristic)

        val write = async(start = CoroutineStart.UNDISPATCHED) { session.events.first { it is DeviceEvent.GattWritten } }
        session.write(characteristic, byteArrayOf(0x01, 0x02), withResponse = false)
        val writeEvent = write.await() as DeviceEvent.GattWritten
        assertArrayEquals(byteArrayOf(0x01, 0x02), writeEvent.data)
        assertTrue(!writeEvent.withResponse)

        session.close()
    }

    @Test
    fun notifications_areTaggedWithTheirCharacteristic() = runBlocking {
        val connection = FakeBleConnection()
        val session = DefaultDeviceRuntime(clock).open(device, connection)
        val service = connection.discoverServices().single()
        val characteristic = BleCharacteristic(service.uuid, FakeBleConnection.DEFAULT_CHARACTERISTIC_UUID)
        val received = async(start = CoroutineStart.UNDISPATCHED) { session.events.first { it is DeviceEvent.NotificationReceived } }

        launch(start = CoroutineStart.UNDISPATCHED) { session.notifications(characteristic).first() }
        connection.emitNotification(characteristic, byteArrayOf(0x2A))

        val event = received.await() as DeviceEvent.NotificationReceived
        assertArrayEquals(byteArrayOf(0x2A), event.data)
        session.close()
    }
}

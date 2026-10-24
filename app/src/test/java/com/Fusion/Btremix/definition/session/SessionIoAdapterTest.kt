package com.Fusion.Btremix.definition.session

import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleService
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.FakeBleConnection
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same bounded JSON program runs through both transport adapters. */
class SessionIoAdapterTest {
    private val device = BleDevice("AA:BB:CC:DD", "Session adapter")

    @Test
    fun sameSessionSendProgramWorksOverGattAndRfcomm() = runBlocking {
        val factory = DefinitionSessionFactory(
            DefaultDeviceRuntime(Clock.systemUTC()),
            Dispatchers.Unconfined,
        )

        val gattConnection = FakeBleConnection(SERVICES)
        val gatt = factory.open(device, gattConnection, definition(gatt = true))
        assertEquals(ActionResult.Success(null), gatt.execute(DeviceAction("run")))
        assertArrayEquals(byteArrayOf(1, 2), gattConnection.writes.single().data)
        gatt.close()

        val streamConnection = FakeRfcommConnection()
        val stream = factory.openStream(device, streamConnection, definition(gatt = false))
        assertEquals(ActionResult.Success(null), stream.execute(DeviceAction("run")))
        assertArrayEquals(byteArrayOf(1, 2), streamConnection.writes.single())
        stream.close()
    }

    private fun definition(gatt: Boolean): com.Fusion.Btremix.definition.api.LoadedDeviceDefinition {
        val transport = if (gatt) {
            """{"service":"$SERVICE_UUID","characteristic":"$CHARACTERISTIC_UUID"}"""
        } else {
            """{"type":"rfcomm","service":"$SERVICE_UUID","bonded":false}"""
        }
        val framing = if (gatt) "" else """
            "framing":{
              "codec":"framed",
              "header":62,
              "trailer":60,
              "checksum":"none",
              "layout":{"messageTypeOffset":0,"sequenceOffset":null,"lengthOffset":1,"lengthBytes":1}
            },
        """.trimIndent()
        return DefinitionJsonCodec.decode(
            """
            {
              "manifest":{
                "id":"test.session.adapter",
                "displayName":"Session adapter",
                "version":"1.0.0",
                "schemaVersion":5,
                "matchers":[{"type":"namePrefix","value":"Session"}]
              },
              "protocol":{
                "transport":$transport,
                $framing
                "transactions":{}
              },
              "states":{},
              "actions":{
                "run":{
                  "displayName":"Run",
                  "script":[{"op":"session.send","bytes":"01 02"}]
                }
              }
            }
            """.trimIndent(),
        )
    }

    private companion object {
        const val SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb"
        const val CHARACTERISTIC_UUID = "00002a19-0000-1000-8000-00805f9b34fb"
        val CHARACTERISTIC = BleCharacteristic(
            serviceUuid = UUID.fromString(SERVICE_UUID),
            uuid = UUID.fromString(CHARACTERISTIC_UUID),
        )
        val SERVICES = listOf(
            BleService(UUID.fromString(SERVICE_UUID), characteristics = listOf(CHARACTERISTIC)),
        )
    }
}

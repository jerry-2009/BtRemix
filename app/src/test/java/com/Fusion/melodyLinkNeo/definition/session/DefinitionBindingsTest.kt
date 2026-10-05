package com.fusion.melodyLinkNeo.definition.session

import com.fusion.melodyLinkNeo.core.bluetooth.api.BleCharacteristic
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleCharacteristicProperty
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleService
import com.fusion.melodyLinkNeo.definition.api.LoadedDeviceDefinition
import com.fusion.melodyLinkNeo.definition.json.DefinitionJsonCodec
import com.fusion.melodyLinkNeo.device.runtime.ActionResult
import com.fusion.melodyLinkNeo.device.runtime.DefaultDeviceRuntime
import com.fusion.melodyLinkNeo.device.runtime.DeviceAction
import com.fusion.melodyLinkNeo.device.runtime.FakeBleConnection
import com.fusion.melodyLinkNeo.device.runtime.RuntimeError
import com.fusion.melodyLinkNeo.device.runtime.StateEntry
import com.fusion.melodyLinkNeo.device.runtime.StateSource
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import com.fusion.melodyLinkNeo.protocol.api.Packet
import com.fusion.melodyLinkNeo.protocol.api.SimplePacketCodec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3: a version 2 definition drives a session without any script. Actions encode a
 * protocol transaction and notification bindings keep a continuous subscription feeding state.
 */
class DefinitionBindingsTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC)
    private val device = BleDevice("AA:BB:CC:DD", "Fusion Buds")
    private val definition: LoadedDeviceDefinition = DefinitionJsonCodec.decode(declarativeDefinition)
    private val factory = DefinitionSessionFactory(DefaultDeviceRuntime(clock), Dispatchers.Unconfined)
    private val packetCodec = SimplePacketCodec(includesSequence = true)

    @Test
    fun declarativeAction_encodesArgumentsAndUpdatesResultState() = runBlocking {
        val connection = FakeBleConnection(SERVICES)
        connection.onWrite = { write ->
            val request = packetCodec.decode(write.data)
            connection.emitNotification(VENDOR_CHARACTERISTIC_MODEL, packetCodec.encode(Packet(145, request.sequence, byteArrayOf(1))))
        }
        val session = factory.open(device, connection, definition)

        val result = session.execute(DeviceAction("anc.setMode", mapOf("value" to StateValue.StringValue("on"))))

        assertEquals(ActionResult.Success(StateValue.StringValue("on")), result)
        val write = connection.writes.single()
        assertArrayEquals(byteArrayOf(17, 0, 1), write.data)
        assertTrue("transport must honour withResponse", write.withResponse)
        assertEquals(StateValue.StringValue("on"), session.state.value("ancMode"))
        assertEquals(StateSource.ACTION, session.state["ancMode"]?.source)
        session.close()
    }

    @Test
    fun notificationBinding_keepsUpdatingStateNotJustOnce() = runBlocking {
        val connection = FakeBleConnection(SERVICES)
        val session = factory.open(device, connection, definition)

        connection.emitNotification(BATTERY_CHARACTERISTIC_MODEL, byteArrayOf(77))
        assertEquals(StateValue.IntValue(77), awaitState(session, "battery", StateValue.IntValue(77)))
        assertEquals(StateSource.NOTIFICATION, session.state["battery"]?.source)

        connection.emitNotification(BATTERY_CHARACTERISTIC_MODEL, byteArrayOf(55))
        assertEquals(StateValue.IntValue(55), awaitState(session, "battery", StateValue.IntValue(55)))

        connection.emitNotification(BATTERY_CHARACTERISTIC_MODEL, byteArrayOf(33))
        assertEquals(StateValue.IntValue(33), awaitState(session, "battery", StateValue.IntValue(33)))

        assertEquals(0, connection.writes.size)
        session.close()
    }

    @Test
    fun notificationBinding_stopsAfterClose() = runBlocking {
        val connection = FakeBleConnection(SERVICES)
        val session = factory.open(device, connection, definition)
        session.close()

        connection.emitNotification(BATTERY_CHARACTERISTIC_MODEL, byteArrayOf(9))

        assertEquals(StateValue.IntValue(82), session.state.value("battery"))
    }

    @Test
    fun bytesStateWithoutDecode_storesRawNotification() = runBlocking {
        val connection = FakeBleConnection(SERVICES)
        val session = factory.open(device, connection, definition)

        connection.emitNotification(VENDOR_CHARACTERISTIC_MODEL, byteArrayOf(1, 2, 3))

        assertEquals(
            StateValue.BytesValue(byteArrayOf(1, 2, 3)),
            awaitState(session, "rawFrame", StateValue.BytesValue(byteArrayOf(1, 2, 3))),
        )
        session.close()
    }

    @Test
    fun transactionTimeout_surfacesAsActionFailed() = runBlocking {
        val connection = FakeBleConnection(SERVICES)
        val session = factory.open(device, connection, definition)

        val result = session.execute(DeviceAction("anc.setMode", mapOf("value" to StateValue.StringValue("on"))))

        val failure = result as ActionResult.Failure
        assertTrue(failure.error is RuntimeError.ActionFailed)
        assertTrue(failure.error.message.contains("Transaction 'anc.set' failed"))
        session.close()
    }

    private suspend fun awaitState(session: com.fusion.melodyLinkNeo.device.runtime.DeviceSession, key: String, expected: StateValue): StateValue =
        withTimeout(2_000) {
            session.state.entries.first { entries -> entries[key]?.value == expected }[key]!!.value
        }

    private companion object {
        const val VENDOR_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val VENDOR_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"
        const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
        const val BATTERY_CHARACTERISTIC = "00002a19-0000-1000-8000-00805f9b34fb"

        val VENDOR_CHARACTERISTIC_MODEL = BleCharacteristic(
            serviceUuid = UUID.fromString(VENDOR_SERVICE),
            uuid = UUID.fromString(VENDOR_CHARACTERISTIC),
            properties = setOf(BleCharacteristicProperty.WRITE, BleCharacteristicProperty.NOTIFY),
        )
        val BATTERY_CHARACTERISTIC_MODEL = BleCharacteristic(
            serviceUuid = UUID.fromString(BATTERY_SERVICE),
            uuid = UUID.fromString(BATTERY_CHARACTERISTIC),
            properties = setOf(BleCharacteristicProperty.READ, BleCharacteristicProperty.NOTIFY),
        )

        val SERVICES = listOf(
            BleService(UUID.fromString(VENDOR_SERVICE), characteristics = listOf(VENDOR_CHARACTERISTIC_MODEL)),
            BleService(UUID.fromString(BATTERY_SERVICE), characteristics = listOf(BATTERY_CHARACTERISTIC_MODEL)),
        )

        val declarativeDefinition = """
            {
              "manifest": {
                "id": "demo.bindings",
                "displayName": "Bindings",
                "version": "2.0.0",
                "schemaVersion": 2,
                "matchers": [{ "type": "namePrefix", "value": "Fusion" }]
              },
              "protocol": {
                "packet": { "includesSequence": true },
                "transport": { "service": "$VENDOR_SERVICE", "characteristic": "$VENDOR_CHARACTERISTIC" },
                "messages": {
                  "setMode": {
                    "fields": [
                      { "name": "mode", "type": "enum", "enumValues": { "0": "off", "1": "on" } }
                    ]
                  }
                },
                "transactions": {
                  "anc.set": { "requestCommand": 17, "requestMessage": "setMode", "expectedCommand": 145, "timeoutMs": 60 }
                }
              },
              "states": {
                "battery": {
                  "type": "integer",
                  "default": 82,
                  "notify": {
                    "service": "$BATTERY_SERVICE",
                    "characteristic": "$BATTERY_CHARACTERISTIC",
                    "decode": { "at": [{ "var": "raw" }, 0] }
                  }
                },
                "ancMode": {
                  "type": "enum",
                  "default": "off",
                  "enumValues": { "on": "On", "off": "Off" }
                },
                "rawFrame": {
                  "type": "bytes",
                  "notify": { "service": "$VENDOR_SERVICE", "characteristic": "$VENDOR_CHARACTERISTIC" }
                }
              },
              "actions": {
                "anc.setMode": {
                  "displayName": "Set mode",
                  "parameters": [{ "name": "value", "type": "enum", "enumValues": { "on": "On", "off": "Off" } }],
                  "resultState": "ancMode",
                  "transaction": "anc.set",
                  "arguments": { "mode": { "arg": "value" } }
                }
              }
            }
        """.trimIndent()
    }
}

package com.Fusion.Btremix.definition.session

import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristicProperty
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleService
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.FakeBleConnection
import com.Fusion.Btremix.device.runtime.RuntimeError
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.device.runtime.StateValue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the milestone 2 wiring: Definition defaults are seeded as `INITIAL` state, action scripts
 * reach BLE and update state, notifications flow back through `ble.subscribe`, and failures stay
 * structured instead of being swallowed.
 */
class DefinitionSessionFactoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC)
    private val device = BleDevice("AA:BB:CC:DD", "Fusion Buds")
    private val definition: LoadedDeviceDefinition = DefinitionJsonCodec.decode(scriptedDefinition)
    private val factory = DefinitionSessionFactory(DefaultDeviceRuntime(clock))

    @Test
    fun open_seedsDefinitionDefaultsAsInitialState() = runBlocking {
        val session = factory.open(device, FakeBleConnection(SERVICES), definition)

        assertEquals(StateValue.IntValue(42), session.state.value("battery"))
        assertEquals(StateSource.INITIAL, session.state["battery"]?.source)
        assertEquals(StateValue.StringValue("transparency"), session.state.value("ancMode"))
        assertEquals(StateValue.BooleanValue(false), session.state.value("muted"))

        session.close()
    }

    @Test
    fun action_scriptWritesBytesAndUpdatesState() = runBlocking {
        val connection = FakeBleConnection(SERVICES)
        val session = factory.open(device, connection, definition)

        val result = session.execute(
            DeviceAction("anc.setMode", mapOf("value" to StateValue.StringValue("noiseCanceling"))),
        )

        assertEquals(ActionResult.Success(StateValue.StringValue("noiseCanceling")), result)
        val write = connection.writes.single()
        assertTrue(write.data.contentEquals(byteArrayOf(0x02)))
        assertEquals(VENDOR_CHARACTERISTIC, write.characteristic.uuid)
        assertEquals(StateValue.StringValue("noiseCanceling"), session.state.value("ancMode"))
        assertEquals(StateSource.ACTION, session.state["ancMode"]?.source)

        session.close()
    }

    @Test
    fun action_isBranchedByArgumentValue() = runBlocking {
        val connection = FakeBleConnection(SERVICES)
        val session = factory.open(device, connection, definition)

        session.execute(DeviceAction("anc.setMode", mapOf("value" to StateValue.StringValue("off"))))
        session.execute(DeviceAction("anc.setMode", mapOf("value" to StateValue.StringValue("noiseCanceling"))))
        session.execute(DeviceAction("anc.setMode", mapOf("value" to StateValue.StringValue("transparency"))))

        assertEquals(listOf<Byte>(0x01, 0x02, 0x01), connection.writes.map { it.data.single() })
        session.close()
    }

    @Test
    fun notificationSubscriptionUpdatesState() = runBlocking {
        val connection = FakeBleConnection(SERVICES)
        connection.emitNotification(BATTERY_CHARACTERISTIC_MODEL, byteArrayOf(77))
        val session = factory.open(device, connection, definition)

        val result = session.execute(DeviceAction("battery.sync"))

        assertEquals(ActionResult.Success(StateValue.IntValue(77)), result)
        assertEquals(StateValue.IntValue(77), session.state.value("battery"))
        assertEquals(0, connection.writes.size) // a subscription must not issue a GATT write
        session.close()
    }

    @Test
    fun actionWithoutScript_returnsStructuredActionNotFound() = runBlocking {
        val session = factory.open(device, FakeBleConnection(SERVICES), definition)

        val result = session.execute(DeviceAction("mute.set", mapOf("value" to StateValue.BooleanValue(true))))

        assertEquals(ActionResult.Failure(RuntimeError.ActionNotFound("mute.set")), result)
        session.close()
    }

    @Test
    fun executeAfterClose_returnsInvalidState() = runBlocking {
        val session = factory.open(device, FakeBleConnection(SERVICES), definition)
        session.close()

        val result = session.execute(DeviceAction("battery.sync"))

        assertTrue((result as ActionResult.Failure).error is RuntimeError.InvalidState)
    }

    @Test
    fun scriptFailure_surfacesAsScriptFailed() = runBlocking {
        // The discovered services do not contain the vendor characteristic the script writes to.
        val session = factory.open(device, FakeBleConnection(emptyList()), definition)

        val result = session.execute(
            DeviceAction("anc.setMode", mapOf("value" to StateValue.StringValue("off"))),
        )

        assertTrue((result as ActionResult.Failure).error is RuntimeError.ScriptFailed)
        session.close()
    }

    private companion object {
        private const val VENDOR_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
        private const val VENDOR_CHARACTERISTIC_TEXT = "0000ffe1-0000-1000-8000-00805f9b34fb"
        private const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
        private const val BATTERY_CHARACTERISTIC_TEXT = "00002a19-0000-1000-8000-00805f9b34fb"

        private val VENDOR_SERVICE_UUID: UUID = UUID.fromString(VENDOR_SERVICE)
        private val VENDOR_CHARACTERISTIC: UUID = UUID.fromString(VENDOR_CHARACTERISTIC_TEXT)
        private val BATTERY_SERVICE_UUID: UUID = UUID.fromString(BATTERY_SERVICE)
        private val BATTERY_CHARACTERISTIC_UUID: UUID = UUID.fromString(BATTERY_CHARACTERISTIC_TEXT)

        private val VENDOR_CHARACTERISTIC_MODEL = BleCharacteristic(
            serviceUuid = VENDOR_SERVICE_UUID,
            uuid = VENDOR_CHARACTERISTIC,
            properties = setOf(BleCharacteristicProperty.WRITE, BleCharacteristicProperty.WRITE_WITHOUT_RESPONSE),
        )
        private val BATTERY_CHARACTERISTIC_MODEL = BleCharacteristic(
            serviceUuid = BATTERY_SERVICE_UUID,
            uuid = BATTERY_CHARACTERISTIC_UUID,
            properties = setOf(BleCharacteristicProperty.READ, BleCharacteristicProperty.NOTIFY),
        )

        private val SERVICES = listOf(
            BleService(VENDOR_SERVICE_UUID, characteristics = listOf(VENDOR_CHARACTERISTIC_MODEL)),
            BleService(BATTERY_SERVICE_UUID, characteristics = listOf(BATTERY_CHARACTERISTIC_MODEL)),
        )

        private val scriptedDefinition = """
            {
              "manifest": {
                "id": "demo.scripted",
                "displayName": "Scripted Device",
                "version": "1.0.0",
                "matchers": [{ "type": "namePrefix", "value": "Fusion" }]
              },
              "states": {
                "battery": { "type": "integer", "default": 42 },
                "ancMode": {
                  "type": "enum",
                  "default": "transparency",
                  "enumValues": { "noiseCanceling": "Noise canceling", "transparency": "Transparency", "off": "Off" }
                },
                "muted": { "type": "boolean", "default": false }
              },
              "actions": {
                "anc.setMode": {
                  "displayName": "Set noise control",
                  "parameters": [{ "name": "value", "type": "enum", "enumValues": { "noiseCanceling": "Noise canceling", "transparency": "Transparency", "off": "Off" } }],
                  "resultState": "ancMode",
                  "script": [
                    {
                      "op": "if",
                      "condition": { "eq": [{ "arg": "value" }, "noiseCanceling"] },
                      "then": [{ "op": "ble.write", "service": "$VENDOR_SERVICE", "characteristic": "$VENDOR_CHARACTERISTIC_TEXT", "value": { "hex": "02" } }],
                      "else": [{ "op": "ble.write", "service": "$VENDOR_SERVICE", "characteristic": "$VENDOR_CHARACTERISTIC_TEXT", "value": { "hex": "01" } }]
                    },
                    { "op": "state.set", "key": "ancMode", "value": { "arg": "value" } },
                    { "op": "return", "value": { "state": "ancMode" } }
                  ]
                },
                "battery.sync": {
                  "displayName": "Sync battery",
                  "resultState": "battery",
                  "script": [
                    { "op": "ble.subscribe", "service": "$BATTERY_SERVICE", "characteristic": "$BATTERY_CHARACTERISTIC_TEXT", "into": "raw" },
                    { "op": "state.set", "key": "battery", "value": { "at": [{ "var": "raw" }, 0] } },
                    { "op": "return", "value": { "state": "battery" } }
                  ]
                },
                "mute.set": {
                  "displayName": "Set mute",
                  "parameters": [{ "name": "value", "type": "boolean" }],
                  "resultState": "muted"
                }
              }
            }
        """.trimIndent()
    }
}

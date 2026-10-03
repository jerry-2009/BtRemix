package com.Fusion.Btremix.definition.simulator

import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.device.runtime.StateValue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 5: a definition can be exercised end to end without hardware. The simulator reuses the
 * production session factory, so declarative actions, scripts and notification bindings all run.
 */
class DeviceSimulatorTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun start_seedsStateAndRunsDeclarativeActionWithAutoAcknowledgement() = runBlocking {
        val simulator = DeviceSimulator(DefinitionJsonCodec.decode(definitionJson), clock)
        val session = simulator.start()

        assertEquals(StateValue.IntValue(88), session.state.value("battery"))

        val result = session.execute(DeviceAction("power.set", mapOf("value" to StateValue.BooleanValue(true))))

        assertEquals(ActionResult.Success(StateValue.BooleanValue(true)), result)
        assertEquals(StateValue.BooleanValue(true), session.state.value("enabled"))
        assertEquals(StateSource.ACTION, session.state["enabled"]?.source)
        val write = simulator.writes.single()
        assertArrayEquals(byteArrayOf(17, 0, 1), write.data)
        simulator.stop()
    }

    @Test
    fun injectedNotification_updatesStateThroughTheBinding() = runBlocking {
        val simulator = DeviceSimulator(DefinitionJsonCodec.decode(definitionJson), clock)
        val session = simulator.start()
        val target = simulator.notifyTargets.first { it.characteristicUuid.toString() == BATTERY_CHARACTERISTIC }

        simulator.injectNotification(target, byteArrayOf(42))

        val entry = withTimeout(2_000) {
            session.state.entries.first { it["battery"]?.value == StateValue.IntValue(42) }["battery"]!!
        }
        assertEquals(StateSource.NOTIFICATION, entry.source)
        simulator.stop()
    }

    @Test
    fun topology_includesScriptCharacteristics() {
        val definition = DefinitionJsonCodec.decode(scriptDefinitionJson)

        val services = topologyOf(definition)
        val characteristicUuids = services.flatMap { service -> service.characteristics.map { it.uuid.toString() } }

        assertTrue(characteristicUuids.contains(SCRIPT_CHARACTERISTIC))
    }

    private companion object {
        const val VENDOR_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val VENDOR_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"
        const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
        const val BATTERY_CHARACTERISTIC = "00002a19-0000-1000-8000-00805f9b34fb"
        const val SCRIPT_CHARACTERISTIC = "0000ffe2-0000-1000-8000-00805f9b34fb"

        val definitionJson = """
            {
              "manifest": {
                "id": "demo.simulated",
                "displayName": "Simulated",
                "version": "2.0.0",
                "schemaVersion": 2,
                "matchers": [{ "type": "namePrefix", "value": "Simulated" }]
              },
              "protocol": {
                "packet": { "includesSequence": true },
                "transport": { "service": "$VENDOR_SERVICE", "characteristic": "$VENDOR_CHARACTERISTIC" },
                "messages": { "setPower": { "fields": [{ "name": "mode", "type": "uint8" }] } },
                "transactions": {
                  "power.set": {
                    "requestCommand": 17,
                    "requestMessage": "setPower",
                    "expectedCommand": 145,
                    "responseMessage": "setPower"
                  }
                }
              },
              "states": {
                "enabled": { "type": "boolean", "default": false },
                "battery": {
                  "type": "integer",
                  "default": 88,
                  "notify": {
                    "service": "$BATTERY_SERVICE",
                    "characteristic": "$BATTERY_CHARACTERISTIC",
                    "decode": { "at": [{ "var": "raw" }, 0] }
                  }
                }
              },
              "actions": {
                "power.set": {
                  "displayName": "Set power",
                  "parameters": [{ "name": "value", "type": "boolean" }],
                  "resultState": "enabled",
                  "transaction": "power.set",
                  "arguments": { "mode": { "arg": "value" } }
                }
              }
            }
        """.trimIndent()

        val scriptDefinitionJson = """
            {
              "manifest": {
                "id": "demo.scripted",
                "displayName": "Scripted",
                "version": "1.0.0",
                "matchers": [{ "type": "namePrefix", "value": "Scripted" }]
              },
              "actions": {
                "ping": {
                  "displayName": "Ping",
                  "script": [
                    { "op": "ble.write", "service": "$VENDOR_SERVICE", "characteristic": "$SCRIPT_CHARACTERISTIC", "value": { "hex": "01" } }
                  ]
                }
              }
            }
        """.trimIndent()
    }
}

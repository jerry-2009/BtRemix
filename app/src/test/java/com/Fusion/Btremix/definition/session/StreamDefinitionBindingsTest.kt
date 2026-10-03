package com.Fusion.Btremix.definition.session

import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.codec.framed.FramedStreamCodec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schema v3 + byte stream: a declarative action becomes an SPP transaction matched by payload type,
 * and `notify.payloadType` keeps state in sync without any vendor code in the runtime.
 */
class StreamDefinitionBindingsTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC)
    private val device = BleDevice("AA:BB:CC:DD:EE:FF", "WF-1000XM3")
    private val definition: LoadedDeviceDefinition = DefinitionJsonCodec.decode(streamDefinition)
    private val factory = DefinitionSessionFactory(DefaultDeviceRuntime(clock), Dispatchers.Unconfined, clock)
    private val codec = FramedStreamCodec(requireNotNull(definition.protocol.framing).toSpec())

    @Test
    fun action_sendsFramedRequestMatchedByPayloadTypeAndAcknowledgesTheResponse() = runBlocking {
        val connection = FakeRfcommConnection()
        connection.onWrite = { bytes ->
            val packet = codec.decode(bytes)
            if (packet.command == 0x0C && packet.payload.firstOrNull()?.toInt()?.and(0xff) == 0x22) {
                connection.emit(codec.encode(Packet(0x0C, 7, byteArrayOf(0x23, 0x01, 0x50, 0x00, 0x4B, 0x00))))
            }
        }
        val session = factory.openStream(device, connection, definition)

        val result = session.execute(DeviceAction("battery.get"))

        assertTrue("expected a successful transaction but was $result", result is ActionResult.Success)
        val request = codec.decode(connection.writes.first())
        assertEquals(0x0C, request.command)
        assertArrayEquals(byteArrayOf(0x22, 0x01), request.payload)
        // The device's response frame must be acknowledged with the complemented inbound sequence.
        assertTrue(connection.writes.any { codec.decode(it) == Packet(0x01, (1 - 7) and 0xff) })
        session.close()
    }

    @Test
    fun successfulTransaction_writesTheActionResultIntoItsResultState() = runBlocking {
        val connection = FakeRfcommConnection()
        connection.onWrite = { bytes ->
            val packet = codec.decode(bytes)
            if (packet.command == 0x0C && packet.payload.firstOrNull()?.toInt()?.and(0xff) == 0x22) {
                connection.emit(codec.encode(Packet(0x0C, 4, byteArrayOf(0x23, 0x01, 0x32, 0x00))))
            }
        }
        val session = factory.openStream(device, connection, definition)

        val result = session.execute(DeviceAction("level.set", mapOf("value" to StateValue.IntValue(7))))

        assertEquals(ActionResult.Success(StateValue.IntValue(7)), result)
        assertEquals(StateValue.IntValue(7), session.state.value("level"))
        assertEquals(StateSource.ACTION, session.state["level"]?.source)
        session.close()
    }

    @Test
    fun writeOnlyTransaction_succeedsWithoutAResponse() = runBlocking {
        // SET commands are acknowledged at the framing layer only; the action must not wait.
        val connection = FakeRfcommConnection()
        val session = factory.openStream(device, connection, definition)

        val result = session.execute(
            DeviceAction("eq.set", mapOf("preset" to StateValue.StringValue("bright"))),
        )

        assertEquals(ActionResult.Success(StateValue.StringValue("bright")), result)
        assertEquals(StateValue.StringValue("bright"), session.state.value("eqPreset"))
        val request = codec.decode(connection.writes.single { codec.decode(it).command == 0x0C })
        assertArrayEquals(byteArrayOf(0x58, 0x01, 0x10, 0x00), request.payload)
        session.close()
    }

    @Test
    fun payloadTypeNotification_updatesStateAndHonoursTheGuard() = runBlocking {
        val connection = FakeRfcommConnection()
        val session = factory.openStream(device, connection, definition)

        connection.emit(codec.encode(Packet(0x0C, 2, byteArrayOf(0x25, 0x01, 0x50, 0x00, 0x5A, 0x00))))
        assertEquals(StateValue.IntValue(80), awaitState(session, "battery.left", StateValue.IntValue(80)))
        assertEquals(StateValue.IntValue(90), awaitState(session, "battery.right", StateValue.IntValue(90)))
        assertEquals(StateSource.NOTIFICATION, session.state["battery.left"]?.source)

        // A case-battery payload (type 2) must not overwrite the earbud levels.
        connection.emit(codec.encode(Packet(0x0C, 3, byteArrayOf(0x25, 0x02, 0x37, 0x00))))
        assertEquals(StateValue.IntValue(80), session.state.value("battery.left"))
        assertEquals(StateValue.IntValue(90), session.state.value("battery.right"))
        session.close()
    }

    @Test
    fun initializeTransactions_runAfterTheSessionBecomesReady() = runBlocking {
        val connection = FakeRfcommConnection()
        val initializing = DefinitionJsonCodec.decode(
            streamDefinition.replace("\"messages\": {", "\"initialize\": [\"protocol.info\"], \"messages\": {"),
        )
        val session = factory.openStream(device, connection, initializing)

        withTimeout(2_000) {
            while (connection.writes.none { wrote ->
                    codec.decode(wrote).let { it.command == 0x0C && it.payload.contentEquals(byteArrayOf(0x00, 0x00)) }
                }) {
                delay(10)
            }
        }
        assertNotNull(session.state)
        session.close()
    }

    @Test
    fun writeOnlyAction_readsBackTheStateItChanged() = runBlocking {
        // A write-only SET that publishes no state must still refresh through its declared read-back.
        val connection = FakeRfcommConnection()
        connection.onWrite = { bytes ->
            val packet = codec.decode(bytes)
            val payload = packet.payload
            if (packet.command == 0x0C && payload.size >= 2 &&
                payload[0].toInt().and(0xff) == 0x66 && payload[1].toInt().and(0xff) == 0x02
            ) {
                connection.emit(codec.encode(Packet(0x0C, 5, byteArrayOf(0x67, 0x02, 0x01, 0x00, 0x00, 0x01, 0x00, 0x0C))))
            }
        }
        val session = factory.openStream(device, connection, definition)

        val result = session.execute(DeviceAction("anc.set", mapOf("mode" to StateValue.StringValue("anc"))))

        assertTrue("expected a successful SET but was $result", result is ActionResult.Success)
        assertEquals(StateValue.StringValue("anc"), awaitState(session, "ancMode", StateValue.StringValue("anc")))
        // The optimistic action result is replaced by the value the peer actually reports.
        assertEquals(StateSource.NOTIFICATION, session.state["ancMode"]?.source)
        val commands = connection.writes.map { codec.decode(it) }.filter { it.command == 0x0C }
        assertEquals("expected the write followed by its read-back but was $commands", 2, commands.size)
        assertEquals(0x68, commands[0].payload[0].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(0x66, 0x02), commands[1].payload)
        session.close()
    }

    @Test
    fun malformedNotification_isIgnoredAndTheSubscriptionSurvives() = runBlocking {
        val connection = FakeRfcommConnection()
        val session = factory.openStream(device, connection, definition)

        // A payload type that matches but is too short for the declared decode must be skipped, not
        // allowed to tear down the continuous subscription.
        connection.emit(codec.encode(Packet(0x0C, 2, byteArrayOf(0x25, 0x01))))
        delay(50)
        connection.emit(codec.encode(Packet(0x0C, 3, byteArrayOf(0x25, 0x01, 0x50, 0x00, 0x5A, 0x00))))

        assertEquals(StateValue.IntValue(80), awaitState(session, "battery.left", StateValue.IntValue(80)))
        assertEquals(StateValue.IntValue(90), session.state.value("battery.right"))
        session.close()
    }

    @Test
    fun lengthDependentDecode_readsBothFirmwareShapes() = runBlocking {
        val connection = FakeRfcommConnection()
        val session = factory.openStream(device, connection, definition)

        // 4-byte V1 shape: [0xE7, 0x02, 0x00, 0x01]
        connection.emit(codec.encode(Packet(0x0C, 2, byteArrayOf(0xE7.toByte(), 0x02, 0x00, 0x01))))
        assertEquals(StateValue.BooleanValue(true), awaitState(session, "upscaling", StateValue.BooleanValue(true)))

        // The same type for another parameter must not touch this state.
        connection.emit(codec.encode(Packet(0x0C, 3, byteArrayOf(0xE7.toByte(), 0x01, 0x00))))
        assertEquals(StateValue.BooleanValue(true), session.state.value("upscaling"))

        // 3-byte V2 shape with the same parameter still resolves through the length fallback.
        connection.emit(codec.encode(Packet(0x0C, 4, byteArrayOf(0xE7.toByte(), 0x02, 0x00))))
        assertEquals(StateValue.BooleanValue(false), awaitState(session, "upscaling", StateValue.BooleanValue(false)))
        session.close()
    }

    private suspend fun awaitState(
        session: com.Fusion.Btremix.device.runtime.ProtocolSession,
        key: String,
        expected: StateValue,
    ): StateValue = withTimeout(2_000) {
        session.state.entries.first { entries -> entries[key]?.value == expected }[key]!!.value
    }

    private companion object {
        const val SPP_SERVICE = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"

        val streamDefinition = """
            {
              "manifest": {
                "id": "demo.stream",
                "displayName": "Stream Device",
                "version": "3.0.0",
                "schemaVersion": 3,
                "capabilities": ["battery"],
                "matchers": [{ "type": "namePrefix", "value": "WF-1000XM3" }]
              },
              "protocol": {
                "packet": { "includesSequence": true },
                "transport": { "type": "rfcomm", "service": "$SPP_SERVICE", "bonded": true },
                "framing": {
                  "codec": "framed",
                  "header": 62,
                  "trailer": 60,
                  "escape": 61,
                  "escapeMask": 239,
                  "checksum": "sum8",
                  "layout": { "messageTypeOffset": 0, "sequenceOffset": 1, "lengthOffset": 2, "lengthBytes": 4, "lengthByteOrder": "big" },
                  "acknowledge": { "messageTypes": [12, 14], "replyMessageType": 1, "sequence": "complement" }
                },
                "messages": {
                  "getBattery": {
                    "fields": [
                      { "name": "payloadType", "type": "uint8" },
                      { "name": "batteryType", "type": "uint8" }
                    ]
                  },
                  "setEq": {
                    "fields": [
                      { "name": "payloadType", "type": "uint8" },
                      { "name": "inquiryType", "type": "uint8" },
                      { "name": "preset", "type": "enum", "enumValues": { "0": "off", "16": "bright" } },
                      { "name": "reserved", "type": "uint8" }
                    ]
                  },
                  "setAnc": {
                    "fields": [
                      { "name": "payloadType", "type": "uint8" },
                      { "name": "asmType", "type": "uint8" },
                      { "name": "modeCode", "type": "uint8" }
                    ]
                  }
                },
                "transactions": {
                  "protocol.info": {
                    "requestCommand": 12,
                    "requestPayload": "0000",
                    "expectedCommand": 12,
                    "expectedPayloadType": 1,
                    "timeoutMs": 300
                  },
                  "battery.get": {
                    "requestCommand": 12,
                    "requestMessage": "getBattery",
                    "expectedCommand": 12,
                    "expectedPayloadType": 35,
                    "timeoutMs": 300
                  },
                  "eq.set": {
                    "requestCommand": 12,
                    "requestMessage": "setEq",
                    "expectsResponse": false
                  },
                  "anc.get": {
                    "requestCommand": 12,
                    "requestPayload": "6602",
                    "expectedCommand": 12,
                    "expectedPayloadTypes": [103, 105],
                    "timeoutMs": 300
                  },
                  "anc.set": {
                    "requestCommand": 12,
                    "requestMessage": "setAnc",
                    "expectsResponse": false
                  }
                }
              },
              "states": {
                "battery.left": {
                  "type": "integer",
                  "default": 0,
                  "notify": {
                    "payloadTypes": [35, 37],
                    "if": { "eq": [{ "at": [{ "var": "raw" }, 1] }, 1] },
                    "decode": { "at": [{ "var": "raw" }, 2] }
                  }
                },
                "battery.right": {
                  "type": "integer",
                  "default": 0,
                  "notify": {
                    "payloadTypes": [35, 37],
                    "if": { "eq": [{ "at": [{ "var": "raw" }, 1] }, 1] },
                    "decode": { "at": [{ "var": "raw" }, 4] }
                  }
                },
                "ancMode": {
                  "type": "enum",
                  "default": "off",
                  "enumValues": { "off": "Off", "anc": "Noise canceling" },
                  "notify": {
                    "payloadTypes": [103, 105],
                    "decode": { "if": [{ "eq": [{ "at": [{ "var": "raw" }, 2] }, 0] }, "off", "anc"] }
                  }
                },
                "level": { "type": "integer", "default": 0 },
                "upscaling": {
                  "type": "boolean",
                  "default": false,
                  "notify": {
                    "payloadTypes": [231, 233],
                    "if": { "eq": [{ "at": [{ "var": "raw" }, 1] }, 2] },
                    "decode": { "if": [
                      { "eq": [{ "len": { "var": "raw" } }, 4] },
                      { "eq": [{ "at": [{ "var": "raw" }, 3] }, 1] },
                      { "eq": [{ "at": [{ "var": "raw" }, 2] }, 1] }
                    ] }
                  }
                },
                "eqPreset": {
                  "type": "enum",
                  "default": "off",
                  "enumValues": { "off": "Off", "bright": "Bright" }
                }
              },
              "actions": {
                "battery.get": {
                  "displayName": "Refresh battery",
                  "transaction": "battery.get",
                  "arguments": { "payloadType": 34, "batteryType": 1 }
                },
                "level.set": {
                  "displayName": "Set level",
                  "parameters": [{ "name": "value", "type": "integer" }],
                  "resultState": "level",
                  "result": { "arg": "value" },
                  "transaction": "battery.get",
                  "arguments": { "payloadType": 34, "batteryType": 1 }
                },
                "eq.set": {
                  "displayName": "Set equalizer",
                  "parameters": [{ "name": "preset", "type": "enum", "enumValues": { "off": "Off", "bright": "Bright" } }],
                  "resultState": "eqPreset",
                  "result": { "arg": "preset" },
                  "transaction": "eq.set",
                  "arguments": { "payloadType": 88, "inquiryType": 1, "preset": { "arg": "preset" }, "reserved": 0 }
                },
                "anc.set": {
                  "displayName": "Set noise control",
                  "parameters": [{ "name": "mode", "type": "enum", "enumValues": { "off": "Off", "anc": "Noise canceling" } }],
                  "resultState": "ancMode",
                  "result": { "arg": "mode" },
                  "transaction": "anc.set",
                  "refresh": ["anc.get"],
                  "arguments": { "payloadType": 104, "asmType": 2, "modeCode": 1 }
                }
              },
              "ui": { "children": [{ "type": "value", "state": "battery.left" }] }
            }
        """.trimIndent()
    }
}

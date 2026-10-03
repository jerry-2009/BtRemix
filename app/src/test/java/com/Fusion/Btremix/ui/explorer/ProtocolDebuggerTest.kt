package com.Fusion.Btremix.ui.explorer

import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.SimplePacketCodec
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 4: the debugger decodes packets the definition understands and rejects the rest. */
class ProtocolDebuggerTest {
    private val definition: LoadedDeviceDefinition = DefinitionJsonCodec.decode(definitionJson)
    private val debugger = ProtocolDebugger(definition)
    private val codec = SimplePacketCodec(includesSequence = true)

    @Test
    fun decode_requestPacket_reportsFields() {
        val decode = debugger.decode(codec.encode(Packet(0x11, 3, byteArrayOf(0x02))))

        assertNull(decode.error)
        assertEquals(0x11, decode.command)
        assertEquals(3, decode.sequence)
        assertEquals("setMode", decode.messageName)
        assertEquals(listOf(DecodedField("mode", "noiseCanceling (2)")), decode.fields)
    }

    @Test
    fun decode_responsePacket_usesResponseMessage() {
        val decode = debugger.decode(codec.encode(Packet(0x91, 3, byteArrayOf(0x02))))

        assertNull(decode.error)
        assertEquals("setMode", decode.messageName)
    }

    @Test
    fun decode_unknownCommand_reportsReason() {
        val decode = debugger.decode(codec.encode(Packet(0x55, 1)))

        assertEquals(0x55, decode.command)
        assertNotNull(decode.error)
        assertTrue(decode.error!!.contains("No message declared"))
    }

    @Test
    fun decode_mismatchedPayload_reportsReason() {
        val decode = debugger.decode(codec.encode(Packet(0x11, 1)))

        assertEquals("setMode", decode.messageName)
        assertTrue(decode.error!!.contains("Payload does not match"))
    }

    @Test
    fun decode_nonPacketBytes_reportsNotAPacket() {
        val decode = debugger.decode(byteArrayOf())

        assertNull(decode.command)
        assertTrue(decode.error!!.contains("Not a packet"))
    }

    @Test
    fun monitorEntry_filter_matchesDirectionUuidAndHex() {
        val entry = MonitorEntry(
            timestamp = Instant.parse("2026-10-03T00:00:00Z"),
            direction = MonitorDirection.WRITE,
            serviceUuid = UUID.fromString(VENDOR_SERVICE),
            characteristicUuid = UUID.fromString(VENDOR_CHARACTERISTIC),
            payload = byteArrayOf(0x11, 0x02),
        )

        assertTrue(entry.matches(""))
        assertTrue(entry.matches("write"))
        assertTrue(entry.matches("11 02"))
        assertTrue(entry.matches(VENDOR_CHARACTERISTIC.substring(0, 8)))
        assertTrue(!entry.matches("notify"))
    }

    private companion object {
        const val VENDOR_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val VENDOR_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"

        val definitionJson = """
            {
              "manifest": {
                "id": "demo.debug",
                "displayName": "Debug",
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
                      { "name": "mode", "type": "enum", "enumValues": { "0": "off", "1": "transparency", "2": "noiseCanceling" } }
                    ]
                  }
                },
                "transactions": {
                  "anc.set": {
                    "requestCommand": 17,
                    "requestMessage": "setMode",
                    "expectedCommand": 145,
                    "responseMessage": "setMode"
                  }
                }
              },
              "states": {
                "ancMode": {
                  "type": "enum",
                  "default": "off",
                  "enumValues": { "on": "On", "off": "Off" }
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

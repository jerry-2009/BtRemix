package com.Fusion.Btremix.definition

import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.loader.DefinitionLoader
import com.Fusion.Btremix.definition.loader.StringDefinitionSource
import com.Fusion.Btremix.definition.matcher.DefinitionMatcher
import com.Fusion.Btremix.definition.validator.DefinitionValidationException
import com.Fusion.Btremix.protocol.api.EnumValue
import com.Fusion.Btremix.protocol.api.Field
import com.Fusion.Btremix.protocol.api.Message
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefinitionRuntimeTest {
    @Test
    fun jsonDefinition_buildsStatesActionsAndUiSchema() {
        val definition = DefinitionJsonCodec.decode("""
            {"manifest":{"id":"ui.demo","displayName":"UI demo","version":"1","matchers":[{"type":"namePrefix","value":"Demo"}]},
             "states":{"enabled":{"type":"boolean","default":true},"mode":{"type":"enum","default":"eco","enumValues":{"eco":"Eco"}}},
             "actions":{"setMode":{"displayName":"Set mode","parameters":[{"name":"value","type":"enum","enumValues":{"eco":"Eco"}}],"resultState":"mode"}},
             "ui":{"title":"Controls","children":[{"type":"switch","state":"enabled","action":"setMode"},{"type":"value","state":"mode"}]}}
        """.trimIndent())
        assertEquals(2, definition.states.size)
        assertEquals("Controls", definition.ui.title)
        assertEquals(1, definition.actions["setMode"]?.parameters?.size)
    }

    @Test
    fun validator_rejectsUnknownUiStateAndAction() {
        val invalid = """
            {"manifest":{"id":"ui.demo","displayName":"UI demo","version":"1","matchers":[{"type":"namePrefix","value":"Demo"}]},
             "states":{"enabled":{"type":"boolean"}}, "actions":{},
             "ui":{"children":[{"type":"switch","state":"missing","action":"missing"}]}}
        """.trimIndent()
        try {
            DefinitionJsonCodec.decode(invalid)
            error("Expected validation failure")
        } catch (error: DefinitionValidationException) {
            assertTrue(error.errors.any { it.path == "ui.children[0].state" })
            assertTrue(error.errors.any { it.path == "ui.children[0].action" })
        }
    }
    @Test
    fun jsonDefinition_buildsProtocolCodecAndTransaction() {
        val definition = DefinitionJsonCodec.decode(definitionJson)
        val factory = definition.protocolFactory()
        @Suppress("UNCHECKED_CAST")
        val command = factory.schema("request").fields.first() as Field<Int>
        @Suppress("UNCHECKED_CAST")
        val mode = factory.schema("request").fields[1] as Field<EnumValue>
        val message = Message.builder()
            .set(command, 1)
            .set(mode, EnumValue(2, "active"))
            .build()

        val packet = factory.encodePacket("setMode", message)
        assertEquals(16, packet.command)
        assertArrayEquals(byteArrayOf(1, 2), packet.payload)
        val transaction = factory.transaction("setMode", message)
        assertEquals(144, transaction.expectedCommand)
        assertEquals(1, transaction.retries)
    }

    @Test
    fun matcher_prefersHigherPriorityServiceRule() {
        val definition = DefinitionJsonCodec.decode(definitionJson)
        val scan = BleScanResult(
            BleDevice("AA:BB", "Fusion Buds"),
            rssi = -40,
            serviceUuids = listOf(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")),
        )

        val match = DefinitionMatcher.rank(definition, scan)
        assertEquals(100, match?.priority)
        assertTrue(DefinitionMatcher.matches(definition, scan))
    }

    @Test
    fun matcher_assignsStrongerDefaultsWhenPriorityIsOmitted() {
        val json = """
            {"manifest":{"id":"demo.defaults","displayName":"Defaults","version":"1","matchers":[
              {"type":"namePrefix","value":"Fusion"},
              {"type":"serviceUuid","value":"0000180f-0000-1000-8000-00805f9b34fb"}
            ]}}
        """.trimIndent()
        val definition = DefinitionJsonCodec.decode(json)
        val scan = BleScanResult(
            BleDevice("AA:BB", "Fusion Buds"),
            rssi = -40,
            serviceUuids = listOf(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")),
        )
        assertEquals(200, DefinitionMatcher.rank(definition, scan)?.priority)
    }

    @Test
    fun loader_acceptsStringSourceAndValidatorReportsInvalidDefinitions() {
        val loader = DefinitionLoader()
        assertEquals("demo.fusion", loader.load(StringDefinitionSource(definitionJson), "ignored").id)

        val invalid = definitionJson.replace("demo.fusion", "bad id")
        try {
            DefinitionJsonCodec.decode(invalid)
            error("Expected validation failure")
        } catch (error: DefinitionValidationException) {
            assertTrue(error.errors.any { it.path == "manifest.id" })
        }
    }

    private companion object {
        val definitionJson = """
            {
              "manifest": {
                "id": "demo.fusion",
                "displayName": "Fusion Buds",
                "version": "1.0.0",
                "capabilities": ["battery", "mode"],
                "matchers": [
                  {"type": "namePrefix", "value": "Fusion", "priority": 10},
                  {"type": "serviceUuid", "value": "0000180f-0000-1000-8000-00805f9b34fb", "priority": 100}
                ]
              },
              "protocol": {
                "endianness": "little",
                "packet": {"includesSequence": true},
                "messages": {
                  "request": {"fields": [
                    {"name": "mode", "type": "uint8"},
                    {"name": "state", "type": "enum", "enumValues": {"1": "idle", "2": "active"}}
                  ]}
                },
                "transactions": {
                  "setMode": {"requestCommand": 16, "requestMessage": "request", "expectedCommand": 144, "timeoutMs": 500, "retries": 1}
                }
              }
            }
        """.trimIndent()
    }
}

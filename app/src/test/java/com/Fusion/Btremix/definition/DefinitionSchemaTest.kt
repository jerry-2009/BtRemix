package com.Fusion.Btremix.definition

import com.Fusion.Btremix.definition.api.DefinitionSchema
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.validator.DefinitionValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the version 2 schema gate: old packages stay valid, new fields need an explicit version. */
class DefinitionSchemaTest {
    @Test
    fun missingSchemaVersion_isTreatedAsVersionOne() {
        val definition = DefinitionJsonCodec.decode(scriptOnlyDefinition)

        assertEquals(DefinitionSchema.VERSION_SCRIPT_ONLY, definition.manifest.schemaVersion)
        assertEquals(1, definition.actions.getValue("anc.setMode").script?.steps?.size)
    }

    @Test
    fun declarativeFields_withoutVersionTwo_areRejected() {
        val failure = validationFailure(declarativeDefinition.replace("\"schemaVersion\": 2,", ""))

        assertTrue(failure.errors.any { it.path == "states.battery.notify" })
        assertTrue(failure.errors.any { it.path == "actions.anc.setMode.transaction" })
        assertTrue(failure.errors.any { it.path == "protocol.transport" })
    }

    @Test
    fun newerSchemaVersion_isRejectedWithStructuredError() {
        val failure = validationFailure(declarativeDefinition.replace("\"schemaVersion\": 2", "\"schemaVersion\": 99"))

        assertTrue(failure.errors.any { it.path == "manifest.schemaVersion" })
    }

    @Test
    fun actionCannotDeclareBothScriptAndTransaction() {
        val mixed = declarativeDefinition.replace(
            "\"transaction\": \"anc.set\",",
            "\"transaction\": \"anc.set\", \"script\": [ { \"op\": \"return\", \"value\": 1 } ],",
        )

        val failure = validationFailure(mixed)

        assertTrue(failure.errors.any { it.path == "actions.anc.setMode.transaction" })
    }

    @Test
    fun transactionArguments_mustCoverRequestMessageFields() {
        val missing = declarativeDefinition.replace(
            "\"arguments\": { \"mode\": { \"arg\": \"value\" } }",
            "\"arguments\": {}",
        )

        val failure = validationFailure(missing)

        assertTrue(failure.errors.any { it.path == "actions.anc.setMode.arguments" })
    }

    @Test
    fun transactionArguments_rejectUnknownFields() {
        val unknown = declarativeDefinition.replace(
            "\"arguments\": { \"mode\": { \"arg\": \"value\" } }",
            "\"arguments\": { \"mode\": { \"arg\": \"value\" }, \"bogus\": 1 }",
        )

        val failure = validationFailure(unknown)

        assertTrue(failure.errors.any { it.path == "actions.anc.setMode.arguments.bogus" })
    }

    @Test
    fun transactionWithoutTransport_isRejected() {
        val removed = declarativeDefinition.replace(Regex("\"transport\"\\s*:\\s*\\{[^}]*\\},"), "")

        val failure = validationFailure(removed)

        assertTrue(failure.errors.any { it.path == "protocol.transport" })
    }

    @Test
    fun streamDefinition_isValid() {
        val definition = DefinitionJsonCodec.decode(streamDefinition)

        assertEquals(DefinitionSchema.VERSION_STREAM, definition.manifest.schemaVersion)
        assertEquals(
            setOf(0x23, 0x25),
            definition.states.getValue("battery.left").notify?.payloadTypes,
        )
    }

    @Test
    fun streamFields_withoutVersionThree_areRejected() {
        val failure = validationFailure(streamDefinition.replace("\"schemaVersion\": 3,", ""))

        assertTrue(failure.errors.any { it.path == "protocol.transport.type" })
        assertTrue(failure.errors.any { it.path == "protocol.framing" })
        assertTrue(failure.errors.any { it.path == "states.battery.left.notify.payloadTypes" })
        assertTrue(failure.errors.any { it.path == "protocol.transactions.battery.get.expectedPayloadTypes" })
    }

    @Test
    fun unknownFramingCodec_isRejected() {
        val failure = validationFailure(streamDefinition.replace("\"framed\"", "\"vendorMagic\""))

        assertTrue(failure.errors.any { it.path == "protocol.framing.codec" })
    }

    @Test
    fun malformedFramingConfiguration_isRejected() {
        val failure = validationFailure(streamDefinition.replace("\"lengthBytes\": 4", "\"lengthBytes\": 9"))

        assertTrue(failure.errors.any { it.path == "protocol.framing" })
    }

    @Test
    fun expectedPayloadType_outsideUint8_isRejected() {
        val failure = validationFailure(streamDefinition.replace("\"expectedPayloadType\": 35", "\"expectedPayloadType\": 300"))

        assertTrue(failure.errors.any { it.path == "protocol.transactions.battery.get.expectedPayloadTypes" })
    }

    @Test
    fun rfcommTransportWithCharacteristic_isRejected() {
        val failure = validationFailure(
            streamDefinition.replace(
                "\"transport\": { \"type\": \"rfcomm\", \"service\": \"$SPP_SERVICE\", \"bonded\": true }",
                "\"transport\": { \"type\": \"rfcomm\", \"service\": \"$SPP_SERVICE\", \"characteristic\": \"$VENDOR_CHARACTERISTIC\", \"bonded\": true }",
            ),
        )

        assertTrue(failure.errors.any { it.path == "protocol.transport.characteristic" })
    }

    @Test
    fun rfcommDefinitionWithScriptAction_isRejected() {
        val failure = validationFailure(
            streamDefinition.replace(
                "\"actions\": {",
                "\"actions\": { \"legacy\": { \"displayName\": \"Legacy\", \"script\": [ { \"op\": \"return\", \"value\": 1 } ] },",
            ),
        )

        assertTrue(failure.errors.any { it.path == "actions.legacy.script" })
    }

    @Test
    fun initializeWithUnknownTransaction_isRejected() {
        val failure = validationFailure(
            streamDefinition.replace("\"initialize\": [\"protocol.info\"]", "\"initialize\": [\"missing.transaction\"]"),
        )

        assertTrue(failure.errors.any { it.path == "protocol.initialize[0]" })
    }

    @Test
    fun actionRefresh_parsesOneNameOrAnOrderedList() {
        val definition = DefinitionJsonCodec.decode(
            streamDefinition.replace(
                "\"actions\": {",
                "\"actions\": { \"battery.sync\": { \"displayName\": \"Sync\", \"transaction\": \"battery.get\", \"refresh\": \"protocol.info\", \"arguments\": { \"payloadType\": 34, \"batteryType\": 1 } },",
            ),
        )

        assertEquals(listOf("protocol.info"), definition.actions.getValue("battery.sync").refresh)
    }

    @Test
    fun actionRefresh_readsBackOnlyOverRfcommAndWithAConstantRequest() {
        // A v2 GATT declaration cannot read back over a byte stream.
        val gatt = validationFailure(
            declarativeDefinition.replace(
                "\"transaction\": \"anc.set\",",
                "\"transaction\": \"anc.set\", \"refresh\": [\"anc.set\"],",
            ),
        )
        assertTrue(gatt.errors.any { it.path == "actions.anc.setMode.refresh" })

        // A read-back is issued without action arguments, so its transaction needs a literal payload.
        val implicitMessage = validationFailure(
            streamDefinition.replace(
                "\"actions\": {",
                "\"actions\": { \"battery.sync\": { \"displayName\": \"Sync\", \"transaction\": \"battery.get\", \"refresh\": [\"battery.get\"], \"arguments\": { \"payloadType\": 34, \"batteryType\": 1 } },",
            ),
        )
        assertTrue(implicitMessage.errors.any { it.path == "actions.battery.sync.refresh[0]" })

        val unknown = validationFailure(
            streamDefinition.replace(
                "\"actions\": {",
                "\"actions\": { \"battery.sync\": { \"displayName\": \"Sync\", \"transaction\": \"battery.get\", \"refresh\": [\"missing\"], \"arguments\": { \"payloadType\": 34, \"batteryType\": 1 } },",
            ),
        )
        assertTrue(unknown.errors.any { it.path == "actions.battery.sync.refresh[0]" })
    }

    private fun validationFailure(json: String): DefinitionValidationException = try {
        DefinitionJsonCodec.decode(json)
        error("Expected validation failure")
    } catch (error: DefinitionValidationException) {
        error
    }

    private companion object {
        const val VENDOR_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val VENDOR_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"
        const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
        const val BATTERY_CHARACTERISTIC = "00002a19-0000-1000-8000-00805f9b34fb"
        const val SPP_SERVICE = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"

        val scriptOnlyDefinition = """
            {
              "manifest": {
                "id": "demo.legacy",
                "displayName": "Legacy",
                "version": "1.0.0",
                "matchers": [{ "type": "namePrefix", "value": "Legacy" }]
              },
              "actions": {
                "anc.setMode": {
                  "displayName": "Set mode",
                  "parameters": [{ "name": "value", "type": "enum", "enumValues": { "on": "On", "off": "Off" } }],
                  "script": [{ "op": "return", "value": { "arg": "value" } }]
                }
              }
            }
        """.trimIndent()

        val declarativeDefinition = """
            {
              "manifest": {
                "id": "demo.declarative",
                "displayName": "Declarative",
                "version": "2.0.0",
                "schemaVersion": 2,
                "matchers": [{ "type": "namePrefix", "value": "Declarative" }]
              },
              "protocol": {
                "packet": { "includesSequence": true },
                "transport": {
                  "service": "$VENDOR_SERVICE",
                  "characteristic": "$VENDOR_CHARACTERISTIC"
                },
                "messages": {
                  "setMode": {
                    "fields": [
                      { "name": "mode", "type": "enum", "enumValues": { "0": "off", "1": "on" } }
                    ]
                  }
                },
                "transactions": {
                  "anc.set": { "requestCommand": 17, "requestMessage": "setMode", "expectedCommand": 145 }
                }
              },
              "states": {
                "battery": {
                  "type": "integer",
                  "default": 50,
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

        val streamDefinition = """
            {
              "manifest": {
                "id": "demo.stream",
                "displayName": "Stream",
                "version": "3.0.0",
                "schemaVersion": 3,
                "matchers": [{ "type": "namePrefix", "value": "Stream" }]
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
                "initialize": ["protocol.info"],
                "messages": {
                  "getBattery": {
                    "fields": [
                      { "name": "payloadType", "type": "uint8" },
                      { "name": "batteryType", "type": "uint8" }
                    ]
                  }
                },
                "transactions": {
                  "protocol.info": { "requestCommand": 12, "requestPayload": "0000", "expectedCommand": 12, "expectedPayloadType": 1, "timeoutMs": 300 },
                  "battery.get": { "requestCommand": 12, "requestMessage": "getBattery", "expectedCommand": 12, "expectedPayloadType": 35, "timeoutMs": 300 }
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
                }
              },
              "actions": {
                "battery.get": {
                  "displayName": "Refresh",
                  "transaction": "battery.get",
                  "arguments": { "payloadType": 34, "batteryType": 1 }
                }
              },
              "ui": { "children": [{ "type": "value", "state": "battery.left" }] }
            }
        """.trimIndent()
    }
}

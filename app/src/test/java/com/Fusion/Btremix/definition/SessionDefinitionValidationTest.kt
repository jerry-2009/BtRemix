package com.Fusion.Btremix.definition

import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.validator.DefinitionValidationException
import com.Fusion.Btremix.definition.validator.DefinitionValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDefinitionValidationTest {
    @Test
    fun declaredSessionStateAndTransactionAreAccepted() {
        val errors = DefinitionValidator.validate(DefinitionJsonCodec.decode(definition(), validate = false))
        assertEquals(emptyList<Any>(), errors)
    }

    @Test
    fun undeclaredStateAndUnknownEndpointAreRejectedAtTheirSteps() {
        val errors = DefinitionValidator.validate(
            DefinitionJsonCodec.decode(
                definition()
                .replace("\"key\":\"ready\"", "\"key\":\"missing\"")
                .replace("\"request\":\"query\"", "\"request\":\"missing\""),
                validate = false,
            ),
        )

        assertTrue(errors.any { it.path == "actions.run.script[0].request" })
        assertTrue(errors.any { it.path == "actions.run.script[1].key" })
    }

    @Test
    fun overlappingExplicitTriggersAreRejected() {
        val failure = validationFailure(
            definition().replace(
                "\"states\": {",
                """
                "triggers": {
                  "one": {
                    "matcher": {"command": 128},
                    "program": [{"op":"session.send","bytes":"01"}]
                  },
                  "two": {
                    "matcher": {"command": 128, "payloadPrefix": "01"},
                    "program": [{"op":"session.send","bytes":"02"}]
                  }
                },
                "states": {
                """.trimIndent(),
            ),
        )

        assertTrue(failure.errors.any { it.path == "triggers.two.matcher" && it.message.contains("overlaps") })
    }

    @Test
    fun legacyBleProgramIsRejectedOnRfcommButSessionProgramIsAccepted() {
        val rfcomm = definition()
            .replace(
                "\"transport\": {\"service\": \"0000180f-0000-1000-8000-00805f9b34fb\", \"characteristic\": \"00002a19-0000-1000-8000-00805f9b34fb\"}",
                "\"transport\": {\"type\":\"rfcomm\",\"service\":\"0000180f-0000-1000-8000-00805f9b34fb\"}",
            )
            .replace("\"schemaVersion\": 5,", "\"schemaVersion\": 5,")
            .replace(
                "\"messages\": {",
                "\"framing\": {\"codec\":\"framed\",\"header\":62,\"trailer\":60,\"checksum\":\"sum8\"},\"messages\": {",
            )
        val accepted = DefinitionValidator.validate(DefinitionJsonCodec.decode(rfcomm, validate = false))
        assertTrue(accepted.none { it.path.startsWith("actions.run.script") })

        val failure = validationFailure(
            rfcomm.replace(
                "\"script\": [",
                "\"script\": [{\"op\":\"ble.read\",\"service\":\"0000180f-0000-1000-8000-00805f9b34fb\",\"characteristic\":\"00002a19-0000-1000-8000-00805f9b34fb\",\"into\":\"value\"},",
            ),
        )
        assertTrue(failure.errors.any { it.path == "actions.run.script[0]" })
    }

    private fun validationFailure(json: String): DefinitionValidationException =
        runCatching { DefinitionJsonCodec.decode(json) }.exceptionOrNull() as? DefinitionValidationException
            ?: error("Expected validation failure")

    private fun definition() = """
        {
          "manifest": {
            "id": "test.session.validation",
            "displayName": "Session validation",
            "version": "1.0.0",
            "schemaVersion": 5,
            "matchers": [{"type":"namePrefix","value":"Session"}]
          },
          "protocol": {
            "transport": {"service": "0000180f-0000-1000-8000-00805f9b34fb", "characteristic": "00002a19-0000-1000-8000-00805f9b34fb"},
            "transactions": {
              "query": {"requestCommand": 1, "expectedCommand": 2}
            }
          },
          "states": {
            "ready": {"type": "boolean", "default": false}
          },
          "actions": {
            "run": {
              "displayName": "Run",
              "script": [
                {"op":"session.request","request":"query","timeoutMs":50},
                {"op":"state.set","key":"ready","value":true}
              ]
            }
          }
        }
    """.trimIndent()
}

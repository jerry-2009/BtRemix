package com.Fusion.Btremix.definition.session

import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleService
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.device.runtime.FakeBleConnection
import com.Fusion.Btremix.device.runtime.RuntimeError
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.SimplePacketCodec
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleTest {
    private val device = BleDevice("AA:BB:CC:DD", "Lifecycle")
    private val codec = SimplePacketCodec(includesSequence = true)
    private val definition = DefinitionJsonCodec.decode(
        definitionJson("""[
          {"transaction":"init.optional","required":false},
          {"transaction":"init.required","required":true}
        ]"""),
    )

    @Test
    fun requiredFailure_closesSessionBeforeReady() = runBlocking {
        val connection = FakeBleConnection(SERVICES)
        val onlyRequired = DefinitionJsonCodec.decode(
            definitionJson("""[{"transaction":"init.required","required":true}]"""),
        )

        val error = runCatching {
            DefinitionSessionFactory(
                DefaultDeviceRuntime(Clock.systemUTC()),
                Dispatchers.Unconfined,
            ).open(device, connection, onlyRequired)
        }.exceptionOrNull()

        assertTrue(error is DefinitionSessionException)
        assertTrue((error as DefinitionSessionException).error is RuntimeError.InitializationFailed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun optionalFailure_isRecordedAndRequiredStepStillReachesReady() = runBlocking {
        val connection = FakeBleConnection(SERVICES)
        connection.onWrite = { write ->
            val request = codec.decode(write.data)
            if (request.command == 0x20) {
                connection.emitNotification(
                    CHARACTERISTIC,
                    codec.encode(Packet(0x21, request.sequence, byteArrayOf(1))),
                )
            }
            // The optional command (0x10) deliberately gets no response.
        }
        val session = DefinitionSessionFactory(
            DefaultDeviceRuntime(Clock.systemUTC()),
            Dispatchers.Unconfined,
        ).open(device, connection, definition)

        assertEquals(DeviceLifecycleState.Ready, session.lifecycle.value)
        assertEquals(1, session.initializationDiagnostics.size)
        assertFalse(session.initializationDiagnostics.single().required)
        assertTrue(session.initializationDiagnostics.single().step.contains("init.optional"))

        session.close()
        session.close()
        assertTrue(connection.disconnected)
    }

    private fun definitionJson(initialization: String) = """
        {
          "manifest": {
            "id": "test.lifecycle",
            "displayName": "Lifecycle",
            "version": "1.0.0",
            "schemaVersion": 5,
            "matchers": [{"type":"namePrefix","value":"Lifecycle"}]
          },
          "protocol": {
            "transport": {
              "service": "$SERVICE_UUID",
              "characteristic": "$CHARACTERISTIC_UUID"
            },
            "transactions": {
              "init.optional": {
                "requestCommand": 16,
                "expectedCommand": 17,
                "timeoutMs": 20
              },
              "init.required": {
                "requestCommand": 32,
                "expectedCommand": 33,
                "timeoutMs": 100
              }
            },
            "initialize": $initialization
          }
        }
    """.trimIndent()

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

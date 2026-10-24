package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.definition.session.DefinitionSessionFactory
import com.Fusion.Btremix.definition.session.FakeRfcommConnection
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.codec.framed.SegmentedFrameCodec
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** P3 acceptance: LabLink L2 is a package plus bounded JSON programs, never Kotlin protocol code. */
class LabReactivePackageTest {
    @Test
    fun reactivePackageParsesProgramsTriggersAndRequiredInitialization() {
        val file = sampleFile()
        assumeTrue("LabLink reactive package is unavailable", file != null)
        val definition = DevicePackageValidator()
            .validate(DevicePackageReader().read(requireNotNull(file)))
            .definition

        assertEquals("lab.reactive", definition.id)
        assertEquals(1, definition.protocol.initialization.size)
        assertTrue(definition.protocol.initialization.single().required)
        assertEquals(setOf("peer.request"), definition.triggers.keys)
        assertTrue(definition.actions.values.all { it.script == null })
    }

    @Test
    fun directionalSplitterAcceptsCoalescedPeerRequestAndResponseInSingleByteChunks() {
        val file = sampleFile()
        assumeTrue("LabLink reactive package is unavailable", file != null)
        val definition = DevicePackageValidator()
            .validate(DevicePackageReader().read(requireNotNull(file)))
            .definition
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val request = codec.encodeRequest(Packet(0x1003, payload = byteArrayOf(0x01)))
        val response = hex("A5 5A 00 90 03 03 00 02 13 CA")
        val accumulator = codec.accumulator()
        val frames = mutableListOf<ByteArray>()

        (request + response).forEach { byte ->
            frames += accumulator.feed(byteArrayOf(byte))
        }

        assertEquals(2, frames.size)
        assertArrayEquals(request, frames[0])
        assertArrayEquals(response, frames[1])
        assertEquals(0x1003, codec.decode(frames[0]).command)
        assertEquals(0x9003, codec.decode(frames[1]).command)
    }

    @Test
    fun reactivePackageAnswersDeviceRequestAndCompletesConditionalInitialization() = runBlocking {
        val file = sampleFile()
        assumeTrue("LabLink reactive package is unavailable", file != null)
        val definition = DevicePackageValidator()
            .validate(DevicePackageReader().read(requireNotNull(file)))
            .definition
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val connection = FakeRfcommConnection()
        val responder = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val response = hex("A5 5A 00 90 03 03 00 02 13 CA")

        connection.onWrite = { bytes ->
            val request = runCatching { codec.decodeRequest(bytes) }.getOrNull()
            if (request?.command == 0x1003) {
                connection.emit(response)
                responder.launch {
                    delay(30)
                    connection.emit(response)
                }
            }
        }

        val factory = DefinitionSessionFactory(
            DefaultDeviceRuntime(Clock.systemUTC()),
            Dispatchers.Unconfined,
        )
        val session = factory.openStream(
            BleDevice("AA:BB:CC:DD:EE:FF", "LabLink reactive"),
            connection,
            definition,
        )

        assertEquals(DeviceLifecycleState.Ready, session.lifecycle.value)
        assertEquals(com.Fusion.Btremix.device.runtime.StateValue.BooleanValue(true), session.state.value("initialized"))
        assertTrue(session.initializationDiagnostics.isEmpty())

        val responsesBeforeTrigger = connection.writes.count { it.contentEquals(response) }
        connection.emit(codec.encodeRequest(Packet(0x1003, payload = byteArrayOf(0x02))))
        delay(30)
        assertEquals(responsesBeforeTrigger, connection.writes.count { it.contentEquals(response) })

        connection.emit(codec.encodeRequest(Packet(0x1003, payload = byteArrayOf(0x01))))
        withTimeout(1_000) {
            while (connection.writes.none { it.contentEquals(response) }) delay(10)
        }
        assertArrayEquals(response, connection.writes.first { it.contentEquals(response) })
        assertFalse(connection.closed)
        val responsesBeforeClose = connection.writes.count { it.contentEquals(response) }

        session.close()
        connection.emit(codec.encodeRequest(Packet(0x1003, payload = byteArrayOf(0x01))))
        delay(50)
        assertEquals(responsesBeforeClose, connection.writes.count { it.contentEquals(response) })
        responder.cancel()
    }

    private fun sampleFile(): File? = CANDIDATES
        .asSequence()
        .map { File(it, "lab.reactive-1.0.0.dcpkg") }
        .firstOrNull(File::isFile)

    private fun hex(value: String): ByteArray =
        value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        val CANDIDATES = listOf(
            File("../../samples"),
            File("../samples"),
            File("samples"),
        )
    }
}

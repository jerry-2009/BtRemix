package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.definition.session.DefinitionSessionFactory
import com.Fusion.Btremix.definition.session.FakeRfcommConnection
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.codec.framed.SegmentedFrameCodec
import java.io.File
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** End-to-end P2 acceptance: LabLink is a package, not Kotlin protocol code. */
class LabLinkPackageTest {
    @Test
    fun labStaticPackageUsesOnlyDeclarativeSegmentsAndMatchesGoldenFrames() {
        val file = sampleFile()
        assumeTrue("LabLink sample package is unavailable", file != null)
        val definition = DevicePackageValidator().validate(DevicePackageReader().read(requireNotNull(file))).definition
        val framing = requireNotNull(definition.protocol.framing)
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec

        assertEquals("lab.static", definition.id)
        assertEquals("segmented", framing.codec)
        assertEquals(2, codec.requestCommandWidthBytes)
        assertArrayEquals(
            hex("A5 5A 10 03 02 00 D5 20"),
            codec.encodeRequest(Packet(0x1003)),
        )
        assertEquals(
            Packet(0x9003, payload = byteArrayOf(0x02)),
            codec.decodeResponse(hex("A5 5A 00 90 03 03 00 02 13 CA")),
        )
        assertTrue(definition.actions.values.all { it.script == null })
    }

    @Test
    fun fakeStreamQueryUpdatesStateWithoutVendorRuntimeBranches() = runBlocking {
        val file = sampleFile()
        assumeTrue("LabLink sample package is unavailable", file != null)
        val definition = DevicePackageValidator().validate(DevicePackageReader().read(requireNotNull(file))).definition
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val connection = FakeRfcommConnection()
        var decodedRequest: Packet? = null
        connection.onWrite = { bytes ->
            decodedRequest = codec.decodeRequest(bytes)
            connection.emit(codec.encodeResponse(Packet(0x9003, payload = byteArrayOf(0x02))))
        }
        val factory = DefinitionSessionFactory(DefaultDeviceRuntime(Clock.systemUTC()), Dispatchers.Unconfined)
        val session = factory.openStream(
            BleDevice("AA:BB:CC:DD:EE:FF", "LabLink static"),
            connection,
            definition,
        )

        val result = session.execute(DeviceAction("query"))

        assertEquals(Packet(0x1003), decodedRequest)
        assertEquals(ActionResult.Success(null), result)
        assertEquals(StateValue.IntValue(2), session.state.value("responseValue"))
        assertFalse(connection.closed)
        session.close()
    }

    private fun sampleFile(): File? = CANDIDATES
        .asSequence()
        .map { File(it, "lab.static-1.0.0.dcpkg") }
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

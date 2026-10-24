package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.definition.session.DefinitionSessionException
import com.Fusion.Btremix.definition.session.DefinitionSessionFactory
import com.Fusion.Btremix.definition.session.FakeRfcommConnection
import com.Fusion.Btremix.definition.api.rfcommServiceCandidates
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.device.runtime.RuntimeError
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.SequenceFunction
import com.Fusion.Btremix.protocol.codec.framed.SegmentedFrameCodec
import com.Fusion.Btremix.protocol.extension.ProtocolExtensionRegistry
import com.Fusion.Btremix.vendor.xiaomi.XiaomiMmaAuth
import com.Fusion.Btremix.vendor.xiaomi.XiaomiMmaAuthExtension
import com.Fusion.Btremix.vendor.xiaomi.XiaomiRcsp
import java.io.File
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** P5 Xiaomi acceptance: package loading, MMA vectors, state sync and authentication failure. */
class XiaomiPackageTest {
    private val challenge = hex("10 20 30 40 50 60 70 80 90 A0 B0 C0 D0 E0 F0 00")
    private val extensionRegistry = ProtocolExtensionRegistry(
        listOf(XiaomiMmaAuthExtension { challenge }),
    )

    @Test
    fun packageDeclaresRfcommMmaAndLoadsWithTheRegisteredExtension() {
        val file = sampleFile()
        assumeTrue("Xiaomi MMA package is unavailable", file != null)

        val definition = DevicePackageValidator(extensionRegistry)
            .validate(DevicePackageReader().read(requireNotNull(file)))
            .definition

        assertEquals("xiaomi.mma", definition.id)
        assertEquals("rfcomm", definition.protocol.transport?.type?.name?.lowercase())
        assertEquals(
            listOf(
                java.util.UUID.fromString("0000FD2D-0000-1000-8000-00805F9B34FB"),
                java.util.UUID.fromString("00001101-0000-1000-8000-008584D01810"),
            ),
            definition.protocol.transport?.rfcommServiceCandidates(),
        )
        assertEquals(SequenceFunction.INCREMENT, definition.protocol.framing?.sequence?.function)
        assertEquals("xiaomi.mma.auth", definition.manifest.extensions.single().id)
        assertTrue(definition.protocol.initialization.first().required)
        assertTrue(file.length() > 0)
    }

    @Test
    fun unregisteredExtension_rejectsOnlyTheXiaomiPackage() {
        val file = sampleFile()
        assumeTrue("Xiaomi MMA package is unavailable", file != null)

        val error = runCatching {
            DevicePackageValidator().validate(DevicePackageReader().read(requireNotNull(file)))
        }.exceptionOrNull() as PackageException

        assertEquals("manifest.extensions[0].id", error.error.path)
        assertTrue(error.error.detail.contains(XiaomiMmaAuthExtension.ID))
    }

    @Test
    fun declarativeMmaLayoutMatchesMeasuredAndDerivedGoldenFrames() {
        val codec = validatedCodec()

        assertArrayEquals(
            hex("FE DC BA C4 02 00 05 90 00 00 00 80 EF"),
            codec.encodeRequest(Packet(0x02, 0x90, hex("00 00 00 80"))),
        )
        assertArrayEquals(
            hex("FE DC BA C4 F3 00 03 91 00 0B EF"),
            codec.encodeRequest(Packet(0xF3, 0x91, hex("00 0B"))),
        )
        assertArrayEquals(
            hex("FE DC BA C4 F2 00 06 92 04 00 0B 02 00 EF"),
            codec.encodeRequest(Packet(0xF2, 0x92, hex("04 00 0B 02 00"))),
        )
        assertArrayEquals(
            hex("FE DC BA C4 50 00 12 93 01 10 20 30 40 50 60 70 80 90 A0 B0 C0 D0 E0 F0 00 EF"),
            codec.encodeRequest(Packet(0x50, 0x93, byteArrayOf(0x01) + challenge)),
        )
        assertEquals(
            Packet(0x02, 0x90, hex("04 07 D5 53 45")),
            codec.decodeResponse(
                hex("FE DC BA 00 02 00 07 00 90 04 07 D5 53 45 EF"),
            ),
        )
    }

    @Test
    fun directionalSplitterRecoversCoalescedMmaFramesInSingleByteChunks() {
        val codec = validatedCodec()
        val request = codec.encodeRequest(Packet(0x02, 0x35, hex("00 00 00 80")))
        val response = codec.encodeResponse(
            Packet(0x02, 0x35, hex("04 07 55 53 45")),
        )
        val accumulator = codec.accumulator()
        val frames = mutableListOf<ByteArray>()

        (request + response).forEach { byte ->
            frames += accumulator.feed(byteArrayOf(byte))
        }

        assertEquals(2, frames.size)
        assertArrayEquals(request, frames[0])
        assertArrayEquals(response, frames[1])
    }

    @Test
    fun fakeDeviceAuthenticatesUpdatesBatteryAndReadsBackNoiseMode() = runBlocking {
        val definition = validatedDefinition()
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val connection = FakeRfcommConnection(name = "Redmi Buds 6 Play")
        val device = FakeMmaPeer(codec, connection)
        connection.onWrite = device::onWrite
        val session = DefinitionSessionFactory(
            DefaultDeviceRuntime(Clock.systemUTC()),
            Dispatchers.Unconfined,
            extensionRegistry = extensionRegistry,
        ).openStream(
            BleDevice("AA:BB:CC:DD:EE:FF", "Redmi Buds 6 Play"),
            connection,
            definition,
        )

        assertEquals(DeviceLifecycleState.Ready, session.lifecycle.value)
        assertEquals(StateValue.BooleanValue(true), session.state.value("authenticated"))
        assertEquals(StateValue.IntValue(85), session.state.value("battery.left"))
        assertEquals(StateValue.IntValue(83), session.state.value("battery.right"))
        assertEquals(StateValue.IntValue(69), session.state.value("battery.case"))
        assertEquals(StateValue.BooleanValue(true), session.state.value("battery.leftCharging"))
        assertEquals(StateValue.BooleanValue(false), session.state.value("battery.rightCharging"))
        assertEquals(StateValue.StringValue("anc"), session.state.value("noiseMode"))

        val result = session.execute(
            DeviceAction("noise.setMode", mapOf("value" to StateValue.StringValue("transparency"))),
        )

        assertEquals(ActionResult.Success(StateValue.StringValue("transparency")), result)
        assertArrayEquals(hex("04 00 0B 02 00"), device.lastSetPayload)
        assertEquals(StateValue.StringValue("transparency"), session.state.value("noiseMode"))
        val sequences = connection.writes.map { codec.decodeRequest(it).sequence!! }
        assertTrue(sequences.zipWithNext().all { (previous, next) -> (previous + 1) and 0xff == next })
        assertFalse(connection.closed)

        session.close()
        assertTrue(connection.closed)
    }

    @Test
    fun redmiBuds6Specialization_usesVerifiedRcspTargetInfoWithoutSppAuthentication() = runBlocking {
        val definition = redmiBuds6Definition()
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val connection = FakeRfcommConnection(name = "Redmi Buds 6")
        val peer = FakeRcspPeer(codec, connection)
        connection.onWrite = peer::onWrite

        val session = DefinitionSessionFactory(
            DefaultDeviceRuntime(Clock.systemUTC()),
            Dispatchers.Unconfined,
            extensionRegistry = extensionRegistry,
        ).openStream(
            BleDevice("AA:BB:CC:DD:EE:FF", "Redmi Buds 6"),
            connection,
            definition,
        )

        assertEquals("xiaomi.redmi_buds6", definition.id)
        assertEquals("Redmi Buds 6", definition.melody?.support?.name)
        assertTrue(definition.manifest.extensions.isEmpty())
        assertArrayEquals(
            hex("FE DC BA C4 02 00 05 07 00 00 00 80 EF"),
            codec.encodeRequest(Packet(0x02, 0x07, hex("00 00 00 80"))),
        )
        assertEquals(StateValue.IntValue(85), session.state.value("battery.left"))
        assertEquals(StateValue.IntValue(83), session.state.value("battery.right"))
        assertEquals(StateValue.IntValue(69), session.state.value("battery.case"))

        val result = session.execute(
            DeviceAction("anc.setMode", mapOf("value" to StateValue.StringValue("anc"))),
        )

        assertEquals(ActionResult.Success(StateValue.StringValue("anc")), result)
        assertArrayEquals(hex("02 04 01"), peer.lastSetPayload)
        assertEquals(StateValue.StringValue("anc"), session.state.value("ancMode"))
        session.close()
    }

    @Test
    fun redmiBuds6_batteryMaskParsesTheMeasuredHardwareResponse() = runBlocking {
        val definition = redmiBuds6Definition()
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val connection = FakeRfcommConnection(name = "Redmi Buds 6")
        connection.onWrite = { bytes ->
            val request = codec.decodeRequest(bytes)
            if (request.command == 0x02) {
                require(request.payload.contentEquals(hex("00 00 00 80")))
                connection.emit(
                    XiaomiRcsp.response(
                        control = XiaomiRcsp.SPP_TARGET_APP,
                        opcode = 0x02,
                        status = 0,
                        sequence = requireNotNull(request.sequence),
                        payload = hex("04 07 50 FF FF"),
                    ),
                )
            }
        }
        val session = DefinitionSessionFactory(
            DefaultDeviceRuntime(Clock.systemUTC()),
            Dispatchers.Unconfined,
            extensionRegistry = extensionRegistry,
        ).openStream(
            BleDevice("AA:BB:CC:DD:EE:FF", "Redmi Buds 6"),
            connection,
            definition,
        )

        assertEquals(StateValue.IntValue(80), session.state.value("battery.left"))
        assertEquals(StateValue.IntValue(0), session.state.value("battery.right"))
        assertEquals(StateValue.IntValue(0), session.state.value("battery.case"))
        assertEquals(StateValue.BooleanValue(false), session.state.value("battery.leftCharging"))
        assertEquals(StateValue.BooleanValue(false), session.state.value("battery.rightCharging"))
        assertEquals(StateValue.BooleanValue(false), session.state.value("battery.caseCharging"))
        session.close()
    }

    @Test
    fun redmiBuds6_targetInfoProbeTimeoutDoesNotFailTheReadySession() = runBlocking {
        val definition = redmiBuds6Definition()
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val connection = FakeRfcommConnection(name = "Redmi Buds 6")
        connection.onWrite = { }

        val session = DefinitionSessionFactory(
            DefaultDeviceRuntime(Clock.systemUTC()),
            Dispatchers.Unconfined,
            extensionRegistry = extensionRegistry,
        ).openStream(
            BleDevice("AA:BB:CC:DD:EE:FF", "Redmi Buds 6"),
            connection,
            definition,
        )

        assertEquals(DeviceLifecycleState.Ready, session.lifecycle.value)
        assertTrue(connection.writes.isNotEmpty())
        assertEquals(0, codec.decodeRequest(connection.writes.first()).sequence)
        assertFalse(connection.closed)
        session.close()
    }

    @Test
    fun redmiBuds6_statusNotificationsUseMelodyLinkTlvLayout() = runBlocking {
        val definition = redmiBuds6Definition()
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val connection = FakeRfcommConnection(name = "Redmi Buds 6")
        connection.onWrite = { }
        val session = DefinitionSessionFactory(
            DefaultDeviceRuntime(Clock.systemUTC()),
            Dispatchers.Unconfined,
            extensionRegistry = extensionRegistry,
        ).openStream(
            BleDevice("AA:BB:CC:DD:EE:FF", "Redmi Buds 6"),
            connection,
            definition,
        )

        connection.emit(codec.encodeRequest(Packet(0x0E, 0x30, hex("04 00 61 32 FF"))))
        connection.emit(codec.encodeRequest(Packet(0x0E, 0x31, hex("02 04 02"))))
        withTimeout(1_000) {
            session.state.entries.first { entries ->
                entries["ancMode"]?.value == StateValue.StringValue("transparency") &&
                    entries["battery.left"]?.value == StateValue.IntValue(97)
            }
        }

        assertEquals(StateValue.StringValue("transparency"), session.state.value("ancMode"))
        assertEquals(StateValue.IntValue(97), session.state.value("battery.left"))
        assertEquals(StateValue.IntValue(50), session.state.value("battery.right"))
        session.close()
    }

    @Test
    fun redmiBuds6_deviceInitiatedC7NotificationUpdatesAncState() = runBlocking {
        val definition = redmiBuds6Definition()
        val connection = FakeRfcommConnection(name = "Redmi Buds 6")
        connection.onWrite = { }
        val session = DefinitionSessionFactory(
            DefaultDeviceRuntime(Clock.systemUTC()),
            Dispatchers.Unconfined,
            extensionRegistry = extensionRegistry,
        ).openStream(
            BleDevice("AA:BB:CC:DD:EE:FF", "Redmi Buds 6"),
            connection,
            definition,
        )

        connection.emit(hex("FE DC BA C7 0E 00 04 31 02 04 01 EF"))
        withTimeout(1_000) {
            session.state.entries.first { entries ->
                entries["ancMode"]?.value == StateValue.StringValue("anc")
            }
        }

        assertEquals(StateValue.StringValue("anc"), session.state.value("ancMode"))
        session.close()
    }

    @Test
    fun redmiBuds6_ancSetResponseIsNotDecodedAsBattery() = runBlocking {
        val definition = redmiBuds6Definition()
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val connection = FakeRfcommConnection(name = "Redmi Buds 6")
        connection.onWrite = { bytes ->
            val request = codec.decodeRequest(bytes)
            val response = when (request.command) {
                0x02 -> hex("04 07 55 53 45")
                0x08 -> hex("04 02 04 01 00")
                else -> error("Unexpected RCSP command 0x${request.command.toString(16)}")
            }
            connection.emit(
                XiaomiRcsp.response(
                    control = XiaomiRcsp.SPP_TARGET_APP,
                    opcode = request.command,
                    status = 0,
                    sequence = requireNotNull(request.sequence),
                    payload = response,
                ),
            )
        }
        val session = DefinitionSessionFactory(
            DefaultDeviceRuntime(Clock.systemUTC()),
            Dispatchers.Unconfined,
            extensionRegistry = extensionRegistry,
        ).openStream(
            BleDevice("AA:BB:CC:DD:EE:FF", "Redmi Buds 6"),
            connection,
            definition,
        )

        val result = session.execute(
            DeviceAction("anc.setMode", mapOf("value" to StateValue.StringValue("anc"))),
        )

        assertEquals(ActionResult.Success(StateValue.StringValue("anc")), result)
        assertEquals(StateValue.IntValue(85), session.state.value("battery.left"))
        assertEquals(StateValue.IntValue(83), session.state.value("battery.right"))
        assertEquals(StateValue.IntValue(69), session.state.value("battery.case"))
        session.close()
    }

    @Test
    fun invalidAuthenticationResponse_failsRequiredInitializationAndClosesTransport() = runBlocking {
        val definition = validatedDefinition()
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val connection = FakeRfcommConnection(name = "Redmi Buds")
        connection.onWrite = { bytes ->
            val request = codec.decodeRequest(bytes)
            if (request.command == 0x50) {
                connection.emit(
                    codec.encodeResponse(
                        Packet(0x50, request.sequence, byteArrayOf(0x01) + ByteArray(16)),
                    ),
                )
            }
        }

        val error = runCatching {
            DefinitionSessionFactory(
                DefaultDeviceRuntime(Clock.systemUTC()),
                Dispatchers.Unconfined,
                extensionRegistry = extensionRegistry,
            ).openStream(
                BleDevice("AA:BB:CC:DD:EE:FF", "Redmi Buds"),
                connection,
                definition,
            )
        }.exceptionOrNull() as DefinitionSessionException

        assertTrue(error.error is RuntimeError.InitializationFailed)
        assertTrue(error.error.message.contains("auth.mismatch"))
        assertTrue(connection.closed)
    }

    private fun validatedCodec(): SegmentedFrameCodec =
        validatedDefinition().protocolFactory().packetCodec as SegmentedFrameCodec

    private fun validatedDefinition() = DevicePackageValidator(extensionRegistry)
        .validate(DevicePackageReader().read(requireNotNull(sampleFile())))
        .definition

    private fun redmiBuds6Definition() = DevicePackageValidator(extensionRegistry)
        .validate(DevicePackageReader().read(requireNotNull(redmiBuds6File())))
        .definition

    private fun redmiBuds6File(): File? = CANDIDATES
        .asSequence()
        .flatMap { directory -> directory.listFiles()?.asSequence() ?: emptySequence() }
        .filter { file -> file.isFile && file.name.startsWith("xiaomi.redmi_buds6-") }
        .maxByOrNull { it.name }

    private fun sampleFile(): File? = CANDIDATES
        .asSequence()
        .map { File(it, "xiaomi.mma-1.0.0.dcpkg") }
        .firstOrNull(File::isFile)

    private inner class FakeMmaPeer(
        private val codec: SegmentedFrameCodec,
        private val connection: FakeRfcommConnection,
    ) {
        var lastSetPayload: ByteArray = byteArrayOf()
            private set
        private var noiseMode = 0x01

        suspend fun onWrite(bytes: ByteArray) {
            val request = codec.decodeRequest(bytes)
            val response = when (request.command) {
                0x50 -> {
                    require(request.payload.first() == 0x01.toByte())
                    byteArrayOf(0x01) + XiaomiMmaAuth.response(request.payload.copyOfRange(1, 17))
                }
                0x51 -> byteArrayOf(0x01)
                0x02 -> hex("04 07 D5 53 45")
                0xF3 -> {
                    require(request.payload.contentEquals(hex("00 0B")))
                    byteArrayOf(0x04, 0x00, 0x0B, noiseMode.toByte(), 0x00)
                }
                0xF2 -> {
                    lastSetPayload = request.payload.clone()
                    noiseMode = request.payload[3].toInt() and 0xff
                    return
                }
                else -> error("Unexpected MMA command 0x${request.command.toString(16)}")
            }
            connection.emit(codec.encodeResponse(Packet(request.command, request.sequence, response)))
        }
    }

    private inner class FakeRcspPeer(
        private val codec: SegmentedFrameCodec,
        private val connection: FakeRfcommConnection,
    ) {
        var lastSetPayload: ByteArray = byteArrayOf()
            private set

        suspend fun onWrite(bytes: ByteArray) {
            val request = codec.decodeRequest(bytes)
            when (request.command) {
                0x02 -> {
                    require(request.payload.contentEquals(hex("00 00 00 80")))
                    connection.emit(
                        XiaomiRcsp.response(
                            control = XiaomiRcsp.SPP_TARGET_APP,
                            opcode = 0x02,
                            status = 0,
                            sequence = requireNotNull(request.sequence),
                            payload = hex("04 07 D5 53 45"),
                        ),
                    )
                }
                0x08 -> {
                    lastSetPayload = request.payload.clone()
                    require(request.payload.contentEquals(hex("02 04 01")))
                    connection.emit(
                        XiaomiRcsp.response(
                            control = XiaomiRcsp.SPP_TARGET_APP,
                            opcode = 0x08,
                            status = 0,
                            sequence = requireNotNull(request.sequence),
                            payload = byteArrayOf(),
                        ),
                    )
                }
                else -> error("Unexpected RCSP command 0x${request.command.toString(16)}")
            }
        }
    }

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

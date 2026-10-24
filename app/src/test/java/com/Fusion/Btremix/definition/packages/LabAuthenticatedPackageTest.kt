package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.definition.session.DefinitionSessionFactory
import com.Fusion.Btremix.definition.session.FakeRfcommConnection
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.codec.framed.SegmentedFrameCodec
import com.Fusion.Btremix.protocol.extension.ProtocolExtensionRegistry
import com.Fusion.Btremix.vendor.testbed.ReferenceChallengeExtension
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

/** P4 acceptance: LabLink L3 is a package plus one explicitly registered extension. */
class LabAuthenticatedPackageTest {
    @Test
    fun packageDeclaresOnlyExtensionMetadataAndCompletesChallengeResponse() = runBlocking {
        val file = sampleFile()
        assumeTrue("LabLink authenticated package is unavailable", file != null)
        val definition = validatedDefinition(requireNotNull(file))
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val connection = FakeRfcommConnection(name = "LabLink authenticated")
        val challenge = hex("10 20 30 40 50 60 70 80")
        val expectedResponse = hex("4A E4 23 41 0E A8 67 05")
        connection.onWrite = { bytes ->
            val request = runCatching { codec.decodeRequest(bytes) }.getOrNull()
            if (request?.command == 0x1004) {
                connection.emit(codec.encodeResponse(Packet(0x9004, payload = challenge)))
            }
        }
        val factory = DefinitionSessionFactory(
            runtime = DefaultDeviceRuntime(Clock.systemUTC()),
            dispatcher = Dispatchers.Unconfined,
            extensionRegistry = ProtocolExtensionRegistry(listOf(ReferenceChallengeExtension())),
        )

        val session = factory.openStream(
            BleDevice("AA:BB:CC:DD:EE:FF", "LabLink authenticated"),
            connection,
            definition,
        )

        assertEquals(DeviceLifecycleState.Ready, session.lifecycle.value)
        assertEquals(StateValue.BooleanValue(true), session.state.value("authenticated"))
        assertEquals(1, definition.manifest.extensions.size)
        assertEquals(ReferenceChallengeExtension.ID, definition.manifest.extensions.single().id)
        assertEquals("default", definition.manifest.extensions.single().entry)
        assertEquals(2, connection.writes.size)
        val authResponse = codec.decodeRequest(connection.writes.last())
        assertEquals(0x1005, authResponse.command)
        assertArrayEquals(expectedResponse, authResponse.payload)
        assertFalse(connection.closed)
        session.close()
    }

    @Test
    fun missingRegistration_reportsManifestExtensionPath() {
        val file = sampleFile()
        assumeTrue("LabLink authenticated package is unavailable", file != null)
        val raw = DevicePackageReader().read(requireNotNull(file))

        val error = runCatching {
            DevicePackageValidator().validate(raw)
        }.exceptionOrNull() as PackageException

        assertEquals(PackageError.Code.DEFINITION_INVALID, error.error.code)
        assertEquals("manifest.extensions[0].id", error.error.path)
        assertTrue(error.error.detail.contains("unsupported extension"))
    }

    @Test
    fun apiVersionMismatch_reportsTheDeclarationField() {
        val file = sampleFile()
        assumeTrue("LabLink authenticated package is unavailable", file != null)
        val raw = DevicePackageReader().read(requireNotNull(file))
        val mismatched = raw.copy(
            definitionJson = raw.definitionJson.replace(
                "\"apiVersion\": 1",
                "\"apiVersion\": 2",
            ),
        )

        val error = runCatching {
            DevicePackageValidator(registry()).validate(mismatched)
        }.exceptionOrNull() as PackageException

        assertEquals("manifest.extensions[0].apiVersion", error.error.path)
    }

    @Test
    fun unknownEntry_reportsTheDeclarationField() {
        val file = sampleFile()
        assumeTrue("LabLink authenticated package is unavailable", file != null)
        val raw = DevicePackageReader().read(requireNotNull(file))
        val unknown = raw.copy(
            definitionJson = raw.definitionJson.replaceFirst(
                "\"entry\": \"default\"",
                "\"entry\": \"undeclared\"",
            ),
        )

        val error = runCatching {
            DevicePackageValidator(registry()).validate(unknown)
        }.exceptionOrNull() as PackageException

        assertEquals("manifest.extensions[0].entry", error.error.path)
    }

    @Test
    fun emptyEntry_reportsTheDeclarationField() {
        val file = sampleFile()
        assumeTrue("LabLink authenticated package is unavailable", file != null)
        val raw = DevicePackageReader().read(requireNotNull(file))
        val empty = raw.copy(
            definitionJson = raw.definitionJson.replaceFirst(
                "\"entry\": \"default\"",
                "\"entry\": \"\"",
            ),
        )

        val error = runCatching {
            DevicePackageValidator(registry()).validate(empty)
        }.exceptionOrNull() as PackageException

        assertEquals("manifest.extensions[0].entry", error.error.path)
    }

    private fun validatedDefinition(file: File) =
        DevicePackageValidator(registry())
            .validate(DevicePackageReader().read(file))
            .definition

    private fun registry() = ProtocolExtensionRegistry(listOf(ReferenceChallengeExtension()))

    private fun sampleFile(): File? = CANDIDATES
        .asSequence()
        .map { File(it, "lab.authenticated-1.0.0.dcpkg") }
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

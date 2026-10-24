package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.definition.session.DefinitionSessionException
import com.Fusion.Btremix.definition.session.DefinitionSessionFactory
import com.Fusion.Btremix.definition.session.FakeRfcommConnection
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.RuntimeError
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.codec.framed.SegmentedFrameCodec
import com.Fusion.Btremix.protocol.extension.ControlledProtocolIo
import com.Fusion.Btremix.protocol.extension.ExtensionResult
import com.Fusion.Btremix.protocol.extension.ExtensionRuntime
import com.Fusion.Btremix.protocol.extension.ProtocolExtension
import com.Fusion.Btremix.protocol.extension.ProtocolExtensionInstance
import com.Fusion.Btremix.protocol.extension.ProtocolExtensionRegistry
import com.Fusion.Btremix.vendor.testbed.ReferenceChallengeExtension
import java.io.File
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionFailureIsolationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun missingRegistration_disablesOnlyTheReferencingDefinition() {
        val directory = File(tempFolder.root, "device-packages")
        val store = DevicePackageStore(directory)
        val manager = DevicePackageManager(
            store = store,
            validator = DevicePackageValidator(),
        )
        store.write("lab.authenticated", authenticatedPackageBytes())
        store.write("vendor.supported", validPackageBytes(id = "vendor.supported"))

        val errors = manager.refreshInstalled()

        assertEquals(listOf("vendor.supported"), manager.registry.all().map { it.packageId })
        assertEquals(1, errors.size)
        assertEquals("manifest.extensions[0].id", errors.single().path)
    }

    @Test
    fun extensionException_failsRequiredInitializationAndClosesTransport() = runBlocking {
        val error = openWith(failingExtension(throws = true))

        assertTrue(error.error is RuntimeError.InitializationFailed)
        assertTrue(error.error.message.contains("Extension '${ReferenceChallengeExtension.ID}' threw"))
    }

    @Test
    fun extensionTimeout_failsRequiredInitializationAndDoesNotChangeLifecycleContract() = runBlocking {
        val error = openWith(failingExtension(timeoutMs = 2_000))

        assertTrue(error.error is RuntimeError.InitializationFailed)
        assertTrue(error.error.message.contains("timed out after 1000 ms"))
    }

    @Test
    fun unmatchedRegistrationVersion_isRejectedBeforeCreate() {
        val extension = CountingExtension(apiVersion = 1)
        val registry = ProtocolExtensionRegistry(listOf(extension))
        val packageFile = authenticatedFile()
        assumeTrue("LabLink authenticated package is unavailable", packageFile != null)
        val raw = authenticatedRaw().copy(
            definitionJson = authenticatedRaw().definitionJson.replace(
                "\"apiVersion\": 1",
                "\"apiVersion\": 2",
            ),
        )

        val error = runCatching {
            DevicePackageValidator(registry).validate(raw)
        }.exceptionOrNull() as PackageException

        assertEquals("manifest.extensions[0].apiVersion", error.error.path)
        assertEquals(0, extension.createCalls)
    }

    private suspend fun openWith(extension: ProtocolExtension): DefinitionSessionException {
        val packageFile = authenticatedFile()
        assumeTrue("LabLink authenticated package is unavailable", packageFile != null)
        val registry = ProtocolExtensionRegistry(listOf(extension))
        val definition = DevicePackageValidator(registry)
            .validate(DevicePackageReader().read(requireNotNull(packageFile)))
            .definition
        val codec = definition.protocolFactory().packetCodec as SegmentedFrameCodec
        val connection = FakeRfcommConnection(name = "LabLink authenticated")
        connection.onWrite = { bytes ->
            val request = runCatching { codec.decodeRequest(bytes) }.getOrNull()
            if (request?.command == 0x1004) {
                connection.emit(
                    codec.encodeResponse(
                        Packet(0x9004, payload = hex("10 20 30 40 50 60 70 80")),
                    ),
                )
            }
        }
        val error = runCatching {
            DefinitionSessionFactory(
                runtime = DefaultDeviceRuntime(Clock.systemUTC()),
                dispatcher = Dispatchers.Unconfined,
                extensionRegistry = registry,
            ).openStream(
                BleDevice("AA:BB:CC:DD:EE:FF", "LabLink authenticated"),
                connection,
                definition,
            )
        }.exceptionOrNull() as DefinitionSessionException

        assertTrue(connection.closed)
        assertFalse(error.error.message.isBlank())
        return error
    }

    private fun failingExtension(throws: Boolean = false, timeoutMs: Long = 0): ProtocolExtension =
        object : ProtocolExtension {
            override val id = ReferenceChallengeExtension.ID
            override val apiVersion = 1
            override val entries = setOf(ReferenceChallengeExtension.ENTRY)

            override fun create(runtime: ExtensionRuntime): ProtocolExtensionInstance =
                object : ProtocolExtensionInstance {
                    override suspend fun execute(
                        entry: String,
                        input: ByteArray,
                        io: ControlledProtocolIo,
                    ): ExtensionResult {
                        if (throws) error("deliberate extension failure")
                        delay(timeoutMs)
                        return ExtensionResult.Success()
                    }
                }
        }

    private class CountingExtension(
        override val apiVersion: Int,
    ) : ProtocolExtension {
        var createCalls = 0
        override val id = ReferenceChallengeExtension.ID
        override val entries = setOf(ReferenceChallengeExtension.ENTRY)

        override fun create(runtime: ExtensionRuntime): ProtocolExtensionInstance {
            createCalls++
            return object : ProtocolExtensionInstance {
                override suspend fun execute(
                    entry: String,
                    input: ByteArray,
                    io: ControlledProtocolIo,
                ): ExtensionResult = ExtensionResult.Success()
            }
        }
    }

    private fun authenticatedRaw(): RawDevicePackage =
        DevicePackageReader().read(requireNotNull(authenticatedFile()))

    private fun authenticatedPackageBytes(): ByteArray =
        requireNotNull(authenticatedFile()).readBytes()

    private fun authenticatedFile(): File? = CANDIDATES
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

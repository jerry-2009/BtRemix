package com.Fusion.Btremix.protocol.vectors

import com.Fusion.Btremix.definition.api.DefinitionManifest
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.json.JsonValue
import com.Fusion.Btremix.definition.json.JsonWriter
import com.Fusion.Btremix.definition.packages.DevicePackageReader
import com.Fusion.Btremix.definition.packages.DevicePackageValidator
import com.Fusion.Btremix.definition.validator.DefinitionValidator
import com.Fusion.Btremix.scripting.api.ScriptCodec
import com.Fusion.Btremix.scripting.api.ScriptFormatException
import com.Fusion.Btremix.protocol.codec.framed.SegmentedFrameCodec
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 fixture contract. These probes deliberately assert today's capability boundary; when a later
 * phase closes one, the fixture and this test must be updated together.
 */
class LabLinkPressureFixtureTest {
    private val fixture = VectorJson.resource("lablink.json")
    private val layers = fixture.requiredObject("layers")

    @Test
    fun fixtureIsVendorNeutralAndEveryVectorHasProvenanceAndCoverage() {
        assertEquals("derived", fixture.requiredObject("provenance").requiredString("kind"))
        val allowedClassifications = setOf("data", "script", "extension", "core")
        fixture.requiredArray("gaps").values.map { it.requiredObject("gaps[]") }.forEach { gap ->
            assertTrue(gap.requiredString("classification") in allowedClassifications)
            assertFalse(gap.requiredString("reason").isBlank())
        }

        listOf("l1", "l2", "l3").forEach { layerName ->
            val layer = layers.requiredObject(layerName)
            assertEquals("derived", layer.requiredString("source"))
            assertTrue(layer.requiredArray("coverage").values.isNotEmpty())
        }
        val collections = listOf(
            layers.requiredObject("l1").requiredArray("vectors") to "L1",
            layers.requiredObject("l1").requiredArray("errorVectors") to "L1",
            layers.requiredObject("l1").requiredArray("streamCases") to "L1",
            layers.requiredObject("l2").requiredArray("cases") to "L2",
            layers.requiredObject("l3").requiredArray("cases") to "L3",
        )
        collections.forEach { (values, expectedLayer) ->
            values.values.forEach { raw ->
                val vector = raw.requiredObject("vector")
                assertEquals("derived", vector.requiredString("source"))
                assertEquals(expectedLayer, vector.requiredString("layer"))
                assertTrue(vector.requiredArray("coverage").values.isNotEmpty())
            }
        }
    }

    @Test
    fun labStaticVectorsHonourTheDeclaredWireRules() {
        layers.requiredObject("l1").requiredArray("vectors").values.forEach { raw ->
            val vector = raw.requiredObject("l1.vectors[]")
            val bytes = hexBytes(vector.requiredString("bytesHex"))
            val commandWidth = vector.requiredInt("commandWidthBits") / 8
            val responseOffset = if (vector.requiredString("id") == "response") 1 else 0
            val commandOffset = 2 + responseOffset
            val lengthOffset = commandOffset + commandWidth

            assertEquals("magic", listOf(0xA5, 0x5A), bytes.take(2).map { it.toInt() and 0xff })
            val command = bytes.copyOfRange(commandOffset, lengthOffset).fold(0) { acc, byte ->
                (acc shl 8) or (byte.toInt() and 0xff)
            }
            assertEquals(vector.requiredString("id"), vector.requiredInt("command"), command)
            val length = (bytes[lengthOffset].toInt() and 0xff) or
                ((bytes[lengthOffset + 1].toInt() and 0xff) shl 8)
            assertEquals(
                "${vector.requiredString("id")} length must cover payload plus checksum",
                vector.requiredInt("length"),
                length,
            )
            assertEquals(
                "${vector.requiredString("id")} payload span",
                length,
                bytes.size - (lengthOffset + 2),
            )
            val expectedCrc = ((bytes[bytes.lastIndex - 1].toInt() and 0xff) shl 8) or
                (bytes.last().toInt() and 0xff)
            assertEquals(vector.requiredString("id"), expectedCrc, crc16Xmodem(bytes.copyOf(bytes.size - 2)))
        }
    }

    @Test
    fun labStatic_isAcceptedByTheP2PackageAndSegmentedCodec() {
        val layer = layers.requiredObject("l1")
        val file = sampleFile(layer.requiredString("packageFixture"))
        assumeTrue("LabLink .dcpkg fixture is unavailable", file != null)
        val devicePackage = DevicePackageValidator().validate(DevicePackageReader().read(requireNotNull(file)))
        assertEquals("lab.static", devicePackage.definition.id)
        assertEquals(emptyList<Any>(), DefinitionValidator.validate(devicePackage.definition))
        val codec = devicePackage.definition.protocolFactory().packetCodec as SegmentedFrameCodec

        assertArrayEquals(
            hexBytes(layer.requiredArray("vectors").values.first().requiredObject("vectors[0]").requiredString("bytesHex")),
            codec.encodeRequest(com.Fusion.Btremix.protocol.api.Packet(0x1003)),
        )
        assertEquals(2, codec.responseCommandWidthBytes)
        assertTrue(devicePackage.definition.actions.values.all { it.script == null })
    }

    @Test
    fun labReactive_rfcommScriptIsRejectedAtTheActionPath() {
        val expected = layers.requiredObject("l2").requiredObject("expectedCurrentError")
        val errors = DefinitionValidator.validate(decodeProbe(layers.requiredObject("l2")))

        val error = errors.singleOrNull { it.path == expected.requiredString("path") }
            ?: error("missing ${expected.requiredString("path")}; got ${errors.map { it.path }}")
        assertTrue(error.message.contains("not supported over an rfcomm transport"))
    }

    @Test
    fun labReactive_proposedSessionOpcodesAreNotAcceptedByTheCurrentScriptParser() {
        fun sessionSteps(value: JsonValue): List<JsonValue.Object> = when (value) {
            is JsonValue.Array -> value.values.flatMap(::sessionSteps)
            is JsonValue.Object -> {
                val own = if ((value.values["op"] as? JsonValue.StringValue)?.value?.startsWith("session.") == true) {
                    listOf(value)
                } else {
                    emptyList()
                }
                own + value.values.values.flatMap(::sessionSteps)
            }
            else -> emptyList()
        }

        val proposed = sessionSteps(layers.requiredObject("l2").requiredArray("program")).distinct()
        assertEquals(4, proposed.size)
        proposed.forEach { step ->
            val failure = runCatching {
                ScriptCodec.parse(JsonValue.Array(listOf(step)), "lab.reactive.program")
            }.exceptionOrNull()
            assertTrue("${step.values["op"]} must be rejected in P0", failure is ScriptFormatException)
            assertTrue(requireNotNull(failure).message!!.contains("unsupported operation"))
        }
    }

    @Test
    fun labAuthenticated_extensionDeclarationIsCurrentlyUnrepresented() {
        val layer = layers.requiredObject("l3")
        val probeJson = layer.requiredObject("currentProbeDefinition")
        val manifestJson = probeJson.requiredObject("manifest")
        val extension = manifestJson.requiredArray("extensions").values.single().requiredObject("manifest.extensions[0]")
        assertEquals("lab.authenticated.xor-index", extension.requiredString("id"))
        assertEquals(1, extension.requiredInt("apiVersion"))

        val definition = DefinitionJsonCodec.decode(JsonWriter.write(probeJson), validate = false)
        val errors = DefinitionValidator.validate(definition)
        assertTrue(
            "P1 must reject the not-yet-registered reactive capability",
            errors.any { it.path == "manifest.requires[0]" },
        )
        val manifestFields = DefinitionManifest::class.java.declaredFields.map { it.name }
        assertFalse("P4 would become stale once manifest.extensions is modelled", manifestFields.any { it == "extensions" })
        assertEquals(
            "manifest.extensions[0]",
            layer.requiredObject("expectedCurrentGap").requiredString("path"),
        )
    }

    private fun decodeProbe(layer: JsonValue.Object) = DefinitionJsonCodec.decode(
        JsonWriter.write(layer.requiredObject("currentProbeDefinition")),
        validate = false,
    )

    private fun sampleFile(relativePath: String): File? = SAMPLE_ROOTS
        .asSequence()
        .map { root -> File(root, relativePath) }
        .firstOrNull(File::isFile)

    private fun crc16Xmodem(bytes: ByteArray): Int {
        var crc = 0
        bytes.forEach { byte ->
            crc = crc xor ((byte.toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor 0x1021) and 0xffff
                } else {
                    (crc shl 1) and 0xffff
                }
            }
        }
        return crc
    }

    private companion object {
        val SAMPLE_ROOTS = listOf("../../samples", "../samples", "samples")
    }
}

package com.Fusion.Btremix.definition

import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.json.DefinitionJsonException
import com.Fusion.Btremix.definition.validator.DefinitionValidationException
import com.Fusion.Btremix.protocol.api.ChecksumSpec
import com.Fusion.Btremix.protocol.api.ChecksumAlgorithm
import com.Fusion.Btremix.protocol.api.ChecksumCoverage
import com.Fusion.Btremix.protocol.api.Endianness
import com.Fusion.Btremix.protocol.api.FramingLayout
import com.Fusion.Btremix.protocol.api.FramingSpec
import com.Fusion.Btremix.protocol.api.FrameSplitterRegistry
import com.Fusion.Btremix.protocol.api.FrameSplitterSpec
import com.Fusion.Btremix.protocol.api.LegacyFramingAdapter
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.codec.framed.FramedStreamCodec
import com.Fusion.Btremix.protocol.codec.framed.SegmentedFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyFramingAdapterTest {
    @Test
    fun legacyChecksumStringsAdaptToEquivalentDescriptions() {
        assertEquals(
            ChecksumSpec(function = "none", width = 0),
            ChecksumSpec.legacy("none"),
        )
        assertEquals(
            ChecksumSpec(function = "sum8", width = 8),
            ChecksumSpec.legacy("SUM8"),
        )
        assertEquals(
            ChecksumSpec(function = "xor8", width = 8),
            ChecksumSpec.legacy("xor8"),
        )
    }

    @Test
    fun allLegacySchemaVersionsLoadWithoutRuntimeOrRequiresDeclarations() {
        (1..4).forEach { version ->
            val definition = DefinitionJsonCodec.decode(legacyDefinition(version))
            assertEquals(version, definition.manifest.schemaVersion)
            assertTrue(definition.manifest.requires.isEmpty())
        }
    }

    @Test
    fun structuredCrcAndCapabilityDeclarationLoadTogether() {
        val definition = DefinitionJsonCodec.decode(
            streamDefinition(
                checksum = """
                    {
                      "function": "crc",
                      "width": 16,
                      "polynomial": "0x1021",
                      "initial": 0,
                      "reflectIn": false,
                      "reflectOut": false,
                      "xorOut": 0,
                      "outputByteOrder": "little"
                    }
                """.trimIndent(),
                requires = """["checksum.crc", "splitter.delimited"]""",
            ),
        )

        val framing = requireNotNull(definition.protocol.framing)
        assertEquals("crc", framing.checksumSpec?.function)
        assertEquals(16, framing.checksumSpec?.width)
        assertEquals(listOf("checksum.crc", "splitter.delimited"), definition.manifest.requires)
        assertEquals("crc", framing.toSpec().checksum?.function)
    }

    @Test
    fun legacyFramingAdapterProducesTheSameBytesAsTheFrozenCodec() {
        val legacy = FramingSpec(
            header = 0x3E,
            trailer = 0x3C,
            checksum = ChecksumSpec(ChecksumAlgorithm.SUM8),
            layout = FramingLayout(
                messageTypeOffset = 0,
                sequenceOffset = 1,
                lengthOffset = 2,
                lengthBytes = 4,
                lengthByteOrder = Endianness.BIG,
            ),
        )
        val adapted = FramingSpec(
            checksum = ChecksumSpec(
                function = "sum8",
                width = 8,
                coverage = ChecksumCoverage.SEGMENTS,
            ),
            splitter = FrameSplitterSpec(FrameSplitterRegistry.LENGTH_PREFIXED),
            segmented = LegacyFramingAdapter.requestAndResponse(legacy),
        )
        val packet = Packet(0x0C, 7, byteArrayOf(0x22, 0x01))

        assertArrayEquals(
            FramedStreamCodec(legacy).encode(packet),
            SegmentedFrameCodec(adapted).encodeRequest(packet),
        )
    }

    @Test
    fun unknownCapabilityReportsTheRequiredIndexAndAvailableNames() {
        val failure = validationFailure(
            streamDefinition(requires = """["checksum.crc", "vendor.magic"]"""),
        )

        val error = failure.errors.single { it.path == "manifest.requires[1]" }
        assertTrue(error.message.contains("unsupported capability 'vendor.magic'"))
        assertTrue(error.message.contains("checksum.crc"))
    }

    @Test
    fun duplicateCapabilityAndUnsupportedRuntimeAreRejected() {
        val duplicate = validationFailure(
            streamDefinition(requires = """["splitter.delimited", "splitter.delimited"]"""),
        )
        assertTrue(duplicate.errors.any {
            it.path == "manifest.requires[1]" && it.message.contains("duplicate capability")
        })

        val runtime = validationFailure(streamDefinition(runtimeApi = 99))
        assertTrue(runtime.errors.any { it.path == "manifest.runtimeApi" })
    }

    @Test
    fun unknownStructuredChecksumFieldIsNotSilentlyIgnored() {
        val thrown = runCatching {
            DefinitionJsonCodec.decode(
                streamDefinition(
                    checksum = """{"function":"sum8","width":8,"mystery":true}""",
                ),
            )
        }.exceptionOrNull()

        assertTrue(thrown is DefinitionJsonException)
        assertEquals(
            "protocol.framing.checksum.mystery",
            (thrown as DefinitionJsonException).path,
        )
    }

    @Test
    fun invalidCrcParametersReportNormalizedParameterPaths() {
        val failure = validationFailure(
            streamDefinition(
                checksum = """{"function":"crc","width":16,"polynomial":0,"initial":70000}""",
            ),
        )

        assertTrue(failure.errors.any { it.path == "protocol.framing.checksum.polynomial" })
        assertTrue(failure.errors.any { it.path == "protocol.framing.checksum.initial" })
    }

    @Test
    fun unknownChecksumFunctionAndOutputOrderUseExactPaths() {
        val function = validationFailure(
            streamDefinition(checksum = """{"function":"magic","width":8}"""),
        )
        val functionError = function.errors.single {
            it.path == "protocol.framing.checksum.function"
        }
        assertTrue(functionError.message.contains("checksum 'magic'"))
        assertTrue(functionError.message.contains("available"))
        assertTrue(functionError.message.contains("sum8"))

        val order = runCatching {
            DefinitionJsonCodec.decode(
                streamDefinition(
                    checksum = """{"function":"crc","width":16,"polynomial":4129,"outputByteOrder":"middle"}""",
                ),
            )
        }.exceptionOrNull()
        assertTrue(order is DefinitionJsonException)
        assertEquals(
            "protocol.framing.checksum.outputByteOrder",
            (order as DefinitionJsonException).path,
        )
    }

    private fun validationFailure(json: String): DefinitionValidationException {
        val thrown = runCatching { DefinitionJsonCodec.decode(json) }.exceptionOrNull()
        assertTrue("expected DefinitionValidationException but was $thrown", thrown is DefinitionValidationException)
        return thrown as DefinitionValidationException
    }

    private fun legacyDefinition(version: Int) = """
        {
          "manifest": {
            "id": "legacy.v$version",
            "displayName": "Legacy $version",
            "version": "1.0.$version",
            "schemaVersion": $version,
            "matchers": [{"type":"namePrefix","value":"Legacy"}]
          }
        }
    """.trimIndent()

    private fun streamDefinition(
        checksum: String = """{"function":"sum8","width":8}""",
        requires: String = """["checksum.sum8","splitter.delimited"]""",
        runtimeApi: Int = 1,
    ) = """
        {
          "manifest": {
            "id": "p1.stream",
            "displayName": "P1 stream",
            "version": "1.0.0",
            "schemaVersion": 3,
            "runtimeApi": $runtimeApi,
            "requires": $requires,
            "matchers": [{"type":"namePrefix","value":"P1"}]
          },
          "protocol": {
            "transport": {
              "type": "rfcomm",
              "service": "956c7b26-d49a-4ba8-b03f-b17d393cb6e2",
              "bonded": false
            },
            "framing": {
              "codec": "framed",
              "header": 165,
              "trailer": 90,
              "checksum": $checksum,
              "splitter": {"function":"delimited","maxFrameBytes":4096},
              "layout": {
                "messageTypeOffset": 0,
                "sequenceOffset": null,
                "lengthOffset": 1,
                "lengthBytes": 1,
                "lengthByteOrder": "big"
              }
            }
          }
        }
    """.trimIndent()
}

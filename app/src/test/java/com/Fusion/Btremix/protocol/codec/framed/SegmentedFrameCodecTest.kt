package com.Fusion.Btremix.protocol.codec.framed

import com.Fusion.Btremix.protocol.api.ChecksumCoverage
import com.Fusion.Btremix.protocol.api.ChecksumSpec
import com.Fusion.Btremix.protocol.api.Endianness
import com.Fusion.Btremix.protocol.api.FrameLayoutSpec
import com.Fusion.Btremix.protocol.api.FrameSegmentRole
import com.Fusion.Btremix.protocol.api.FrameSegmentSpec
import com.Fusion.Btremix.protocol.api.FrameSplitterRegistry
import com.Fusion.Btremix.protocol.api.FrameSplitterSpec
import com.Fusion.Btremix.protocol.api.FramingSpec
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.SegmentedFramingSpec
import com.Fusion.Btremix.protocol.api.StreamCodecRegistry
import com.Fusion.Btremix.protocol.api.FrameLayoutRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedFrameCodecTest {
    private val codec = SegmentedFrameCodec(framing(commandWidthBytes = 2))

    @Test
    fun requestAndResponseGoldenVectorsRoundTripByteForByte() {
        val request = Packet(command = 0x1003)
        val response = Packet(command = 0x9003, payload = byteArrayOf(0x02))

        assertArrayEquals(hex("A5 5A 10 03 02 00 D5 20"), codec.encodeRequest(request))
        assertEquals(request, codec.decodeRequest(codec.encodeRequest(request)))
        assertArrayEquals(hex("A5 5A 00 90 03 03 00 02 13 CA"), codec.encodeResponse(response))
        assertEquals(response, codec.decodeResponse(hex("A5 5A 00 90 03 03 00 02 13 CA")))
    }

    @Test
    fun twentyFourBitCommandUsesThreeByteSegmentWithoutTruncation() {
        val wideFraming = framing(commandWidthBytes = 3)
        val wide = SegmentedFrameCodec(wideFraming)
        val packet = Packet(command = 0x123456)

        val encoded = wide.encodeRequest(packet)

        assertArrayEquals(hex("A5 5A 12 34 56 02 00 66 F3"), encoded)
        assertEquals(packet, wide.decodeRequest(encoded))
        assertEquals(3, wide.requestCommandWidthBytes)
        val layout = requireNotNull(wideFraming.segmented).request
        (0..encoded.size).forEach { first ->
            (first..encoded.size).forEach { second ->
                val splitter = LengthPrefixedFrameSplitter(layout, checksumWidthBytes = 2, maxFrameBytes = 4096)
                val frames = splitter.feed(encoded.copyOfRange(0, first)) +
                    splitter.feed(encoded.copyOfRange(first, second)) +
                    splitter.feed(encoded.copyOfRange(second, encoded.size))
                assertEquals(1, frames.size)
                assertArrayEquals(encoded, frames.single())
            }
        }
    }

    @Test
    fun frameLayoutRulesRejectMissingPayloadAndNonContiguousCoverage() {
        val missingPayload = FrameLayoutSpec(
            listOf(
                FrameSegmentSpec(
                    role = FrameSegmentRole.MAGIC,
                    width = 2,
                    constant = hex("A5 5A"),
                    includeInChecksum = true,
                ),
                FrameSegmentSpec(role = FrameSegmentRole.COMMAND, width = 2, includeInChecksum = true),
                FrameSegmentSpec(role = FrameSegmentRole.LENGTH, width = 2, order = Endianness.LITTLE),
            ),
        )
        val issues = FrameLayoutRules.validate(missingPayload, checksumWidth = 2)
        assertTrue(issues.any { it.parameter == "segments" && it.message.contains("payload") })

        val gapped = FrameLayoutSpec(
            listOf(
                FrameSegmentSpec(
                    role = FrameSegmentRole.MAGIC,
                    width = 2,
                    constant = hex("A5 5A"),
                    includeInChecksum = true,
                ),
                FrameSegmentSpec(role = FrameSegmentRole.COMMAND, width = 2),
                FrameSegmentSpec(role = FrameSegmentRole.LENGTH, width = 2, order = Endianness.LITTLE),
                FrameSegmentSpec(role = FrameSegmentRole.PAYLOAD, includeInLength = true, includeInChecksum = true),
                FrameSegmentSpec(role = FrameSegmentRole.CHECKSUM, width = 2, includeInLength = true),
            ),
        )
        assertTrue(
            FrameLayoutRules.validate(gapped, checksumWidth = 2)
                .any { it.parameter == "segments[0].includeInChecksum" },
        )
    }

    @Test
    fun segmentedCodecAndLengthSplitterAreRegisteredByName() {
        assertTrue(SegmentedFrameCodec.NAME in StreamCodecRegistry.names)
        assertTrue(FrameSplitterRegistry.LENGTH_PREFIXED in FrameSplitterRegistry.names)
    }

    @Test
    fun multipleConstantSegmentsKeepTheirDeclaredOffsets() {
        val request = FrameLayoutSpec(
            listOf(
                FrameSegmentSpec(
                    role = FrameSegmentRole.MAGIC,
                    width = 3,
                    constant = hex("FE DC BA"),
                ),
                FrameSegmentSpec(
                    role = FrameSegmentRole.CONSTANT,
                    name = "type",
                    width = 1,
                    constant = hex("C4"),
                    constantAlternatives = listOf(hex("C7")),
                ),
                FrameSegmentSpec(role = FrameSegmentRole.COMMAND, width = 1),
                FrameSegmentSpec(role = FrameSegmentRole.LENGTH, width = 2, order = Endianness.BIG),
                FrameSegmentSpec(role = FrameSegmentRole.SEQUENCE, width = 1, includeInLength = true),
                FrameSegmentSpec(role = FrameSegmentRole.PAYLOAD, includeInLength = true),
                FrameSegmentSpec(
                    role = FrameSegmentRole.CONSTANT,
                    name = "trailer",
                    width = 1,
                    constant = hex("EF"),
                ),
            ),
        )
        val response = FrameLayoutSpec(
            listOf(
                FrameSegmentSpec(
                    role = FrameSegmentRole.MAGIC,
                    width = 3,
                    constant = hex("FE DC BA"),
                ),
                FrameSegmentSpec(
                    role = FrameSegmentRole.CONSTANT,
                    name = "type",
                    width = 1,
                    constant = hex("00"),
                ),
                FrameSegmentSpec(role = FrameSegmentRole.COMMAND, width = 1),
                FrameSegmentSpec(role = FrameSegmentRole.LENGTH, width = 2, order = Endianness.BIG),
                FrameSegmentSpec(role = FrameSegmentRole.OPAQUE, name = "status", width = 1, includeInLength = true),
                FrameSegmentSpec(role = FrameSegmentRole.SEQUENCE, width = 1, includeInLength = true),
                FrameSegmentSpec(role = FrameSegmentRole.PAYLOAD, includeInLength = true),
                FrameSegmentSpec(
                    role = FrameSegmentRole.CONSTANT,
                    name = "trailer",
                    width = 1,
                    constant = hex("EF"),
                ),
            ),
        )
        val spec = FramingSpec(
            splitter = FrameSplitterSpec(FrameSplitterRegistry.LENGTH_PREFIXED),
            segmented = SegmentedFramingSpec(request, response),
        )
        val segmented = SegmentedFrameCodec(spec)

        assertTrue(FrameLayoutRules.validate(request, checksumWidth = 0).isEmpty())
        assertArrayEquals(
            hex("FE DC BA C4 02 00 05 90 00 00 00 80 EF"),
            segmented.encodeRequest(Packet(0x02, 0x90, hex("00 00 00 80"))),
        )
        assertArrayEquals(
            hex("FE DC BA 00 02 00 03 00 90 01 EF"),
            segmented.encodeResponse(Packet(0x02, 0x90, hex("01"))),
        )

        val notification = hex("FE DC BA C7 0E 00 04 31 02 04 01 EF")
        val split = segmented.accumulator().feed(notification)
        assertEquals(1, split.size)
        assertEquals(
            Packet(0x0E, 0x31, hex("02 04 01")),
            segmented.decode(split.single()),
        )
    }

    companion object {
        fun framing(commandWidthBytes: Int = 2): FramingSpec {
            val request = FrameLayoutSpec(
                listOf(
                    magic(),
                    FrameSegmentSpec(role = FrameSegmentRole.COMMAND, width = commandWidthBytes, includeInChecksum = true),
                    FrameSegmentSpec(
                        role = FrameSegmentRole.LENGTH,
                        width = 2,
                        order = Endianness.LITTLE,
                        includeInChecksum = true,
                    ),
                    FrameSegmentSpec(role = FrameSegmentRole.PAYLOAD, includeInLength = true, includeInChecksum = true),
                    FrameSegmentSpec(role = FrameSegmentRole.CHECKSUM, width = 2, includeInLength = true),
                ),
            )
            val response = FrameLayoutSpec(
                listOf(
                    magic(),
                    FrameSegmentSpec(role = FrameSegmentRole.OPAQUE, name = "status", width = 1, includeInChecksum = true),
                    FrameSegmentSpec(role = FrameSegmentRole.COMMAND, width = commandWidthBytes, includeInChecksum = true),
                    FrameSegmentSpec(
                        role = FrameSegmentRole.LENGTH,
                        width = 2,
                        order = Endianness.LITTLE,
                        includeInChecksum = true,
                    ),
                    FrameSegmentSpec(role = FrameSegmentRole.PAYLOAD, includeInLength = true, includeInChecksum = true),
                    FrameSegmentSpec(role = FrameSegmentRole.CHECKSUM, width = 2, includeInLength = true),
                ),
            )
            return FramingSpec(
                checksum = ChecksumSpec(
                    function = "crc",
                    width = 16,
                    polynomial = 0x1021,
                    coverage = ChecksumCoverage.SEGMENTS,
                ),
                splitter = FrameSplitterSpec(FrameSplitterRegistry.LENGTH_PREFIXED, maxFrameBytes = 4096),
                segmented = SegmentedFramingSpec(request, response),
            )
        }

        private fun magic() = FrameSegmentSpec(
            role = FrameSegmentRole.MAGIC,
            width = 2,
            constant = hex("A5 5A"),
            includeInChecksum = true,
        )

        fun hex(value: String): ByteArray =
            value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

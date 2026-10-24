package com.Fusion.Btremix.protocol.api

import com.Fusion.Btremix.protocol.codec.framed.FramedStreamCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumRegistryTest {
    private val check = "123456789".encodeToByteArray()

    @Test
    fun builtInCrcVectorsRemainStableAtEightAndSixteenBits() {
        assertEquals(0xF4, ChecksumRegistry.create(crc8()).calculate(check))
        assertEquals(0x31C3, ChecksumRegistry.create(crc16()).calculate(check))
    }

    @Test
    fun reflectionParametersProduceTheStandardReflectedVectors() {
        val crc16Kermit = ChecksumSpec(
            function = "crc",
            width = 16,
            polynomial = 0x1021,
            reflectIn = true,
            reflectOut = true,
        )
        val crc8Rohc = ChecksumSpec(
            function = "crc",
            width = 8,
            polynomial = 0x07,
            initial = 0xFF,
            reflectIn = true,
            reflectOut = true,
        )

        assertEquals(0x2189, ChecksumRegistry.create(crc16Kermit).calculate(check))
        assertEquals(0xD0, ChecksumRegistry.create(crc8Rohc).calculate(check))

        val inputOnly = crc16().copy(reflectIn = true)
        val outputOnly = crc16().copy(reflectOut = true)
        assertEquals(0x9184, ChecksumRegistry.create(inputOnly).calculate(check))
        assertEquals(0xC38C, ChecksumRegistry.create(outputOnly).calculate(check))
    }

    @Test
    fun registryRejectsInvalidWidthParametersAndUnknownFunctionsWithUsefulDiagnostics() {
        val badWidth = runCatching {
            ChecksumRegistry.create(ChecksumSpec(function = "crc", width = 32, polynomial = 0x04C11DB7))
        }.exceptionOrNull()
        assertTrue(badWidth?.message.orEmpty().contains("width: must be 8 or 16"))

        val badPolynomial = runCatching {
            ChecksumRegistry.create(ChecksumSpec(function = "crc", width = 16))
        }.exceptionOrNull()
        assertTrue(badPolynomial?.message.orEmpty().contains("polynomial"))

        val unknown = runCatching {
            ChecksumRegistry.create(ChecksumSpec(function = "crc24", width = 24))
        }.exceptionOrNull()
        assertTrue(unknown?.message.orEmpty().contains("crc24"))
        assertTrue(unknown?.message.orEmpty().contains("crc"))
        assertTrue(unknown?.message.orEmpty().contains("sum8"))
    }

    @Test
    fun framedCodecWritesAndReadsMultiByteChecksumInConfiguredOrder() {
        val big = framing(Endianness.BIG)
        val little = framing(Endianness.LITTLE)
        val packet = Packet(command = 0x10, payload = byteArrayOf(0x01, 0x02))

        val bigFrame = FramedStreamCodec(big).encode(packet)
        val littleFrame = FramedStreamCodec(little).encode(packet)
        val body = byteArrayOf(0x10, 0x02, 0x01, 0x02)
        val expected = ChecksumRegistry.create(requireNotNull(big.checksum)).calculate(body)

        assertArrayEquals(
            byteArrayOf((expected ushr 8 and 0xff).toByte(), (expected and 0xff).toByte()),
            bigFrame.copyOfRange(bigFrame.size - 3, bigFrame.size - 1),
        )
        assertArrayEquals(
            byteArrayOf((expected and 0xff).toByte(), (expected ushr 8 and 0xff).toByte()),
            littleFrame.copyOfRange(littleFrame.size - 3, littleFrame.size - 1),
        )
        assertEquals(packet, FramedStreamCodec(big).decode(bigFrame))
        assertEquals(packet, FramedStreamCodec(little).decode(littleFrame))
    }

    @Test
    fun noneIsResolvedByTheRegistryAndAddsNoChecksumBytes() {
        val spec = framing(Endianness.BIG).copy(checksum = ChecksumSpec(function = "none", width = 0))
        val codec = FramedStreamCodec(spec)
        val frame = codec.encode(Packet(command = 0x10, payload = byteArrayOf(0x01)))

        assertArrayEquals(
            byteArrayOf(0xA5.toByte(), 0x10, 0x01, 0x01, 0x5A),
            frame,
        )
        assertEquals(Packet(command = 0x10, payload = byteArrayOf(0x01)), codec.decode(frame))
    }

    private fun crc8() = ChecksumSpec(
        function = "crc",
        width = 8,
        polynomial = 0x07,
    )

    private fun crc16() = ChecksumSpec(
        function = "crc",
        width = 16,
        polynomial = 0x1021,
    )

    private fun framing(order: Endianness) = FramingSpec(
        header = 0xA5,
        trailer = 0x5A,
        checksum = crc16().copy(outputByteOrder = order),
        layout = FramingLayout(
            messageTypeOffset = 0,
            sequenceOffset = null,
            lengthOffset = 1,
            lengthBytes = 1,
        ),
    )
}

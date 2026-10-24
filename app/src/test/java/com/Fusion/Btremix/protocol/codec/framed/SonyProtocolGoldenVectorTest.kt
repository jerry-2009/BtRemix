package com.Fusion.Btremix.protocol.codec.framed

import com.Fusion.Btremix.definition.api.DefinitionManifest
import com.Fusion.Btremix.definition.api.DefinitionSchema
import com.Fusion.Btremix.definition.api.DeviceMatchRule
import com.Fusion.Btremix.definition.api.FramingDefinition
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.ProtocolDefinition
import com.Fusion.Btremix.protocol.FakeByteStream
import com.Fusion.Btremix.protocol.api.Endianness
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.stream.StreamTransport
import com.Fusion.Btremix.protocol.vectors.VectorJson
import com.Fusion.Btremix.protocol.vectors.hexBytes
import com.Fusion.Btremix.protocol.vectors.optionalInt
import com.Fusion.Btremix.protocol.vectors.requiredArray
import com.Fusion.Btremix.protocol.vectors.requiredInt
import com.Fusion.Btremix.protocol.vectors.requiredObject
import com.Fusion.Btremix.protocol.vectors.requiredString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-02: all wire-sensitive Sony assertions are sourced from a machine-readable fixture. The test
 * intentionally constructs the codec through the production Definition factory.
 */
class SonyProtocolGoldenVectorTest {
    private val fixture = VectorJson.resource("sony-baseline.json")
    private val codec = SonyFixtureDefinition.create().protocolFactory().packetCodec as FramedStreamCodec
    private val vectors = fixture.requiredArray("vectors").values.map { it.requiredObject("vectors[]") }

    @Test
    fun everyVector_encodesDecodesAndAcknowledgesByteForByte() {
        vectors.forEach { vector ->
            val id = vector.requiredString("id")
            val packet = vector.packet()
            val expectedWire = hexBytes(vector.requiredString("wireHex"))

            assertArrayEquals(id, expectedWire, codec.encode(packet))
            assertEquals(id, packet, codec.decode(expectedWire))

            val expectedAck = vector.requiredObject("expectedAck")
            val ackPacket = Packet(
                command = expectedAck.requiredInt("command"),
                sequence = expectedAck.requiredInt("sequence"),
                payload = hexBytes(expectedAck.requiredString("payloadHex")),
            )
            assertEquals(id, listOf(ackPacket), codec.automaticReplies(packet))
            assertArrayEquals(id, hexBytes(expectedAck.requiredString("wireHex")), codec.encode(ackPacket))
        }
    }

    @Test
    fun streamTransport_handlesSplitAndCoalescedFramesWithOneAckPerCommand() = runBlocking {
        val streamCase = fixture.requiredObject("streamCases")
        val splitAt = streamCase.requiredInt("splitAtBytes")
        val selected = streamCase.requiredArray("coalescedVectors").values.map { reference ->
            vectors.single { it.requiredString("id") == reference.requiredString("coalescedVectors[]") }
        }
        val first = hexBytes(selected[0].requiredString("wireHex"))
        val second = hexBytes(selected[1].requiredString("wireHex"))
        val stream = FakeByteStream()
        val transport = StreamTransport(stream, codec, Dispatchers.Unconfined)
        val packets = mutableListOf<Packet>()
        val job = launch(Dispatchers.Unconfined) { transport.packets().collect { packets += it } }
        stream.awaitSubscriber()

        stream.emit(first.copyOfRange(0, splitAt))
        yield()
        assertTrue("a half frame must not be emitted", packets.isEmpty())
        assertTrue("a half frame must not be acknowledged", stream.writes.isEmpty())

        stream.emit(first.copyOfRange(splitAt, first.size) + second)
        yield()
        assertEquals(selected.map { it.packet() }, packets)
        assertEquals(selected.size, stream.writes.size)
        selected.forEachIndexed { index, vector ->
            assertArrayEquals(
                vector.requiredString("id"),
                hexBytes(vector.requiredObject("expectedAck").requiredString("wireHex")),
                stream.writes[index],
            )
        }

        job.cancel()
        transport.close()
    }

    @Test
    fun streamTransport_dropsCorruptFrameAndResynchronisesOnTheNextGoldenFrame() = runBlocking {
        val recovery = fixture.requiredObject("streamCases").requiredObject("recovery")
        val corrupt = vectors.single {
            it.requiredString("id") == recovery.requiredString("corruptVector")
        }
        val follower = vectors.single {
            it.requiredString("id") == recovery.requiredString("followedBy")
        }
        val corruptFrame = hexBytes(corrupt.requiredString("wireHex"))
        val corruptAt = corruptFrame.lastIndex - recovery.requiredInt("corruptOffsetFromEnd")
        corruptFrame[corruptAt] = (corruptFrame[corruptAt].toInt() xor recovery.requiredInt("xorMask")).toByte()
        val followerFrame = hexBytes(follower.requiredString("wireHex"))
        val stream = FakeByteStream()
        val transport = StreamTransport(stream, codec, Dispatchers.Unconfined)
        val packets = mutableListOf<Packet>()
        val job = launch(Dispatchers.Unconfined) { transport.packets().collect { packets += it } }
        stream.awaitSubscriber()

        stream.emit(
            hexBytes(recovery.requiredString("noiseHex")) +
                corruptFrame +
                followerFrame,
        )
        yield()

        assertEquals("only the valid follower may reach the session", listOf(follower.packet()), packets)
        assertEquals("the corrupt frame must not generate an ACK", 1, stream.writes.size)
        assertArrayEquals(
            follower.requiredString("id"),
            hexBytes(follower.requiredObject("expectedAck").requiredString("wireHex")),
            stream.writes.single(),
        )

        job.cancel()
        transport.close()
    }

    private fun com.Fusion.Btremix.definition.json.JsonValue.Object.packet(): Packet = Packet(
        command = requiredInt("command"),
        sequence = requiredInt("sequence"),
        payload = hexBytes(requiredString("payloadHex")),
    )

    private object SonyFixtureDefinition {
        fun create(): LoadedDeviceDefinition {
            val fixture = VectorJson.resource("sony-baseline.json")
            val framing = fixture.requiredObject("framing")
            val layout = framing.requiredObject("layout")
            val acknowledgement = framing.requiredObject("acknowledge")
            val definition = FramingDefinition(
                codec = framing.requiredString("codec"),
                header = framing.requiredInt("header"),
                trailer = framing.requiredInt("trailer"),
                escape = framing.optionalInt("escape"),
                escapeMask = framing.requiredInt("escapeMask"),
                checksum = framing.requiredString("checksum"),
                messageTypeOffset = layout.requiredInt("messageTypeOffset"),
                sequenceOffset = layout.optionalInt("sequenceOffset"),
                lengthOffset = layout.requiredInt("lengthOffset"),
                lengthBytes = layout.requiredInt("lengthBytes"),
                lengthByteOrder = when (layout.requiredString("lengthByteOrder")) {
                    "little" -> Endianness.LITTLE
                    "big" -> Endianness.BIG
                    else -> error("Unknown fixture byte order")
                },
                acknowledgeMessageTypes = acknowledgement.requiredArray("messageTypes")
                    .values.map { it.requiredInt("messageTypes[]") },
                acknowledgeReplyMessageType = acknowledgement.requiredInt("replyMessageType"),
                acknowledgeSequence = acknowledgement.requiredString("sequence"),
            )
            return LoadedDeviceDefinition(
                manifest = DefinitionManifest(
                    id = "test.sony-golden",
                    displayName = "Sony golden vectors",
                    version = "1",
                    schemaVersion = DefinitionSchema.VERSION_STREAM,
                    matchers = listOf(DeviceMatchRule.NamePrefix("fixture-only")),
                ),
                protocol = ProtocolDefinition(framing = definition),
            )
        }
    }
}

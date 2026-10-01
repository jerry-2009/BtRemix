package com.Fusion.Btremix.protocol.api

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolRuntimeTest {
    @Test
    fun messageCodec_roundTripsTypedFieldsAndEndianness() {
        val command = Field("command", ProtocolPrimitives.uint8)
        val amount = Field("amount", ProtocolPrimitives.uint16)
        val temperature = Field("temperature", ProtocolPrimitives.int16)
        val ratio = Field("ratio", ProtocolPrimitives.float32)
        val name = Field("name", ProtocolPrimitives.string(4))
        val bytes = Field("bytes", ProtocolPrimitives.bytes(2))
        val schema = MessageSchema(listOf(command, amount, temperature, ratio, name, bytes), Endianness.LITTLE)
        val codec = MessageCodec(schema)
        val message = Message.builder()
            .set(command, 1)
            .set(amount, 0x1234)
            .set(temperature, -2)
            .set(ratio, 1.5f)
            .set(name, "AB")
            .set(bytes, byteArrayOf(0x56, 0x78))
            .build()

        val encoded = codec.encode(message)

        assertArrayEquals(
            byteArrayOf(1, 0x34, 0x12, -2, -1, 0, 0, -64, 0x3f, 65, 66, 0, 0, 0x56, 0x78),
            encoded,
        )
        assertEquals(message, codec.decode(encoded))
        assertArrayEquals(byteArrayOf(0x12, 0x34), MessageCodec(MessageSchema(listOf(amount), Endianness.BIG))
            .encode(Message.builder().set(amount, 0x1234).build()))
    }

    @Test
    fun primitives_supportBitFieldEnumSliceAndConcat() {
        val writer = ByteWriter()
        val flags = ProtocolPrimitives.bitField(0b00111000)
        flags.encode(5, writer, Endianness.LITTLE)
        assertArrayEquals(byteArrayOf(0b00101000), writer.toByteArray())
        assertEquals(5, flags.decode(ByteReader(writer.toByteArray()), Endianness.LITTLE))

        val mode = ProtocolPrimitives.enum(mapOf(1 to "on"))
        assertEquals(EnumValue(1, "on"), mode.decode(ByteReader(byteArrayOf(1)), Endianness.LITTLE))

        val slice = ProtocolPrimitives.slice(1, 2)
        assertArrayEquals(byteArrayOf(2, 3), slice.decode(ByteReader(byteArrayOf(1, 2, 3)), Endianness.LITTLE))

        val concat = ProtocolPrimitives.concat(ProtocolPrimitives.bytes(2), ProtocolPrimitives.bytes(1))
        val data = byteArrayOf(1, 2, 3)
        assertArrayEquals(data, concat.decode(ByteReader(data), Endianness.LITTLE))
        val output = ByteWriter()
        concat.encode(data, output, Endianness.LITTLE)
        assertArrayEquals(data, output.toByteArray())
    }

    @Test
    fun checksum_matchesKnownVectors() {
        val data = "123456789".toByteArray()
        assertEquals(0xf4, Crc8().calculate(data))
        assertEquals(0x31c3, Crc16().calculate(data))
        assertEquals(0xdd, Sum8().calculate(data))
        assertFalse(Crc8().verify(data, 0))
    }

    @Test
    fun packetCodec_roundTripsCommandSequenceAndPayload() {
        val codec = SimplePacketCodec()
        val packet = Packet(0x12, 7, byteArrayOf(0x34, 0x56))
        assertArrayEquals(byteArrayOf(0x12, 7, 0x34, 0x56), codec.encode(packet))
        assertEquals(packet, codec.decode(codec.encode(packet)))
    }

    @Test
    fun transaction_subscribesBeforeWriteAndMatchesCommandAndSequence() = runBlocking {
        val codec = SimplePacketCodec()
        val transport = FakeTransport(codec)
        transport.onWrite = { request ->
            transport.emit(Packet(0x40, request.sequence, byteArrayOf(0)))
            transport.emit(Packet(0x41, (request.sequence!! + 1) and 0xff, byteArrayOf(1)))
            transport.emit(Packet(0x41, request.sequence, byteArrayOf(2)))
        }
        val runtime = TransactionRuntime(transport, codec, codec, 200.milliseconds)

        val result = runtime.execute(TransactionRequest(Packet(0x20), expectedCommand = 0x41))

        assertEquals(TransactionResult.Success(Packet(0x41, 0, byteArrayOf(2)), 1), result)
        assertEquals(1, transport.writes.size)
    }

    @Test
    fun transaction_retriesTimeoutAndReusesSequence() = runBlocking {
        val codec = SimplePacketCodec()
        val transport = FakeTransport(codec)
        transport.onWrite = { request ->
            if (transport.writes.size == 2) transport.emit(Packet(0x33, request.sequence))
        }
        val runtime = TransactionRuntime(transport, codec, codec, 30.milliseconds, defaultRetries = 1)

        val result = runtime.execute(TransactionRequest(Packet(0x22), expectedCommand = 0x33))

        assertEquals(TransactionResult.Success(Packet(0x33, 0), 2), result)
        assertEquals(listOf(0, 0), transport.writes.map { codec.decode(it).sequence })
    }

    @Test
    fun transaction_reportsInvalidPacketAndClosedStream() = runBlocking {
        val codec = SimplePacketCodec()
        val transport = FakeTransport(codec)
        transport.onWrite = { transport.emitRaw(byteArrayOf()) }
        val runtime = TransactionRuntime(transport, codec, codec, 200.milliseconds)

        val invalid = runtime.execute(TransactionRequest(Packet(1)))
        assertTrue((invalid as TransactionResult.Failure).error is TransactionError.InvalidPacket)

        val disconnected = TransactionRuntime(
            object : ProtocolTransport {
                override suspend fun write(data: ByteArray) = Unit
                override fun notifications(): Flow<ByteArray> = emptyFlow()
            }, codec, codec,
        ).execute(TransactionRequest(Packet(1)))
        assertEquals(TransactionResult.Failure(TransactionError.Disconnected), disconnected)
    }

    @Test
    fun transaction_cancellationStopsWaiting() = runBlocking {
        val codec = SimplePacketCodec()
        val transport = FakeTransport(codec)
        val runtime = TransactionRuntime(transport, codec, codec)
        val job = async { runtime.execute(TransactionRequest(Packet(1))) }
        withTimeout(1_000) { transport.writeSignal.await() }
        job.cancel()

        assertTrue(job.isCancelled)
        try {
            job.await()
            error("Expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation is propagated to the caller.
        }
    }

    private class FakeTransport(private val codec: SimplePacketCodec) : ProtocolTransport {
        private val events = MutableSharedFlow<ByteArray>(extraBufferCapacity = 10)
        val writes = mutableListOf<ByteArray>()
        val writeSignal = CompletableDeferred<Unit>()
        var onWrite: suspend (Packet) -> Unit = {}

        override suspend fun write(data: ByteArray) {
            writes += data.clone()
            writeSignal.complete(Unit)
            onWrite(codec.decode(data))
        }

        override fun notifications(): Flow<ByteArray> = events

        suspend fun emit(packet: Packet) = emitRaw(codec.encode(packet))

        suspend fun emitRaw(data: ByteArray) { events.emit(data) }
    }
}

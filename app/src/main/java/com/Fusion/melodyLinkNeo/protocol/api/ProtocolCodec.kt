package com.fusion.melodyLinkNeo.protocol.api

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

enum class Endianness {
    LITTLE,
    BIG;

    internal val byteOrder: ByteOrder
        get() = if (this == LITTLE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
}

/** A typed codec for one value in a binary message. */
interface ValueCodec<T : Any> {
    val fixedSize: Int?

    fun encode(value: T, writer: ByteWriter, endianness: Endianness)

    fun decode(reader: ByteReader, endianness: Endianness): T
}

data class Field<T : Any>(val name: String, val codec: ValueCodec<T>) {
    init {
        require(name.isNotBlank()) { "Field name cannot be blank" }
    }
}

/** Immutable, typed access to the values described by a [MessageSchema]. */
class Message private constructor(private val values: Map<String, Any>) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(field: Field<T>): T =
        values[field.name] as? T
            ?: error("Message does not contain field '${field.name}'")

    fun contains(field: Field<*>): Boolean = values.containsKey(field.name)

    fun asMap(): Map<String, Any> = values.toMap()

    override fun equals(other: Any?): Boolean = other is Message && valuesEqual(values, other.values)

    override fun hashCode(): Int = values.entries.fold(0) { hash, (key, value) ->
        31 * hash + key.hashCode() + valueHash(value)
    }

    override fun toString(): String = "Message(values=$values)"

    companion object {
        fun builder(): Builder = Builder()

        fun of(vararg values: Pair<String, Any>): Message = Message(values.toMap())

        private fun valuesEqual(left: Map<String, Any>, right: Map<String, Any>): Boolean {
            if (left.keys != right.keys) return false
            return left.keys.all { key -> valuesEqual(left.getValue(key), right.getValue(key)) }
        }

        private fun valuesEqual(left: Any, right: Any): Boolean = when {
            left is ByteArray && right is ByteArray -> left.contentEquals(right)
            else -> left == right
        }

        private fun valueHash(value: Any): Int =
            if (value is ByteArray) value.contentHashCode() else value.hashCode()
    }

    class Builder internal constructor() {
        private val values = linkedMapOf<String, Any>()

        fun <T : Any> set(field: Field<T>, value: T): Builder {
            values[field.name] = value
            return this
        }

        fun build(): Message = Message(values.toMap())
    }
}

class MessageSchema(
    val fields: List<Field<*>>,
    val endianness: Endianness = Endianness.LITTLE,
) {
    init {
        require(fields.map { it.name }.distinct().size == fields.size) {
            "Message field names must be unique"
        }
    }
}

interface Encoder {
    fun encode(message: Message): ByteArray
}

interface Decoder {
    fun decode(bytes: ByteArray): Message
}

/** Encodes and decodes a fixed schema in field order. */
class MessageCodec(private val schema: MessageSchema) : Encoder, Decoder {
    override fun encode(message: Message): ByteArray {
        val writer = ByteWriter()
        schema.fields.forEach { field -> encodeField(field, message, writer) }
        return writer.toByteArray()
    }

    override fun decode(bytes: ByteArray): Message {
        val reader = ByteReader(bytes)
        val builder = Message.builder()
        schema.fields.forEach { field -> decodeField(field, reader, builder) }
        require(reader.remaining == 0) { "Trailing bytes after message: ${reader.remaining}" }
        return builder.build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeField(field: Field<*>, message: Message, writer: ByteWriter) {
        val codec = field.codec as ValueCodec<Any>
        codec.encode(message[field as Field<Any>], writer, schema.endianness)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeField(field: Field<*>, reader: ByteReader, builder: Message.Builder) {
        val codec = field.codec as ValueCodec<Any>
        builder.set(field as Field<Any>, codec.decode(reader, schema.endianness))
    }
}

class ByteWriter {
    private val bytes = ArrayList<Byte>()

    fun writeByte(value: Int) {
        require(value in -128..255) { "Byte value out of range: $value" }
        bytes += value.toByte()
    }

    fun writeBytes(value: ByteArray) { value.forEach { bytes += it } }

    fun writeShort(value: Int, endianness: Endianness) {
        val buffer = ByteBuffer.allocate(Short.SIZE_BYTES).order(endianness.byteOrder)
        writeBytes(buffer.putShort(value.toShort()).array())
    }

    fun writeInt(value: Int, endianness: Endianness) {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES).order(endianness.byteOrder)
        writeBytes(buffer.putInt(value).array())
    }

    fun writeFloat(value: Float, endianness: Endianness) {
        val buffer = ByteBuffer.allocate(Float.SIZE_BYTES).order(endianness.byteOrder)
        writeBytes(buffer.putFloat(value).array())
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

class ByteReader(private val bytes: ByteArray) {
    private var offset = 0
    val remaining: Int get() = bytes.size - offset

    fun readByte(): Int = readBytes(1)[0].toInt()

    fun readUnsignedByte(): Int = readByte() and 0xff

    fun readBytes(length: Int): ByteArray {
        require(length >= 0) { "Length cannot be negative" }
        check(remaining >= length) { "Not enough bytes: need $length, have $remaining" }
        return bytes.copyOfRange(offset, offset + length).also { offset += length }
    }

    fun readShort(endianness: Endianness): Short =
        ByteBuffer.wrap(readBytes(Short.SIZE_BYTES)).order(endianness.byteOrder).short

    fun readUnsignedShort(endianness: Endianness): Int = readShort(endianness).toInt() and 0xffff

    fun readInt(endianness: Endianness): Int =
        ByteBuffer.wrap(readBytes(Int.SIZE_BYTES)).order(endianness.byteOrder).int

    fun readUnsignedInt(endianness: Endianness): Long = readInt(endianness).toLong() and 0xffffffffL

    fun readFloat(endianness: Endianness): Float =
        ByteBuffer.wrap(readBytes(Float.SIZE_BYTES)).order(endianness.byteOrder).float
}

object ProtocolPrimitives {
    val uint8: ValueCodec<Int> = object : ValueCodec<Int> {
        override val fixedSize = 1
        override fun encode(value: Int, writer: ByteWriter, endianness: Endianness) {
            require(value in 0..0xff) { "UInt8 out of range: $value" }
            writer.writeByte(value)
        }
        override fun decode(reader: ByteReader, endianness: Endianness): Int = reader.readUnsignedByte()
    }

    val int8: ValueCodec<Int> = object : ValueCodec<Int> {
        override val fixedSize = 1
        override fun encode(value: Int, writer: ByteWriter, endianness: Endianness) {
            require(value in -128..127) { "Int8 out of range: $value" }
            writer.writeByte(value)
        }
        override fun decode(reader: ByteReader, endianness: Endianness): Int = reader.readByte()
    }

    val uint16: ValueCodec<Int> = object : ValueCodec<Int> {
        override val fixedSize = 2
        override fun encode(value: Int, writer: ByteWriter, endianness: Endianness) {
            require(value in 0..0xffff) { "UInt16 out of range: $value" }
            writer.writeShort(value, endianness)
        }
        override fun decode(reader: ByteReader, endianness: Endianness): Int = reader.readUnsignedShort(endianness)
    }

    val int16: ValueCodec<Int> = object : ValueCodec<Int> {
        override val fixedSize = 2
        override fun encode(value: Int, writer: ByteWriter, endianness: Endianness) {
            require(value in Short.MIN_VALUE..Short.MAX_VALUE) { "Int16 out of range: $value" }
            writer.writeShort(value, endianness)
        }
        override fun decode(reader: ByteReader, endianness: Endianness): Int = reader.readShort(endianness).toInt()
    }

    val uint32: ValueCodec<Long> = object : ValueCodec<Long> {
        override val fixedSize = 4
        override fun encode(value: Long, writer: ByteWriter, endianness: Endianness) {
            require(value in 0L..0xffffffffL) { "UInt32 out of range: $value" }
            writer.writeInt(value.toInt(), endianness)
        }
        override fun decode(reader: ByteReader, endianness: Endianness): Long = reader.readUnsignedInt(endianness)
    }

    val int32: ValueCodec<Int> = object : ValueCodec<Int> {
        override val fixedSize = 4
        override fun encode(value: Int, writer: ByteWriter, endianness: Endianness) = writer.writeInt(value, endianness)
        override fun decode(reader: ByteReader, endianness: Endianness): Int = reader.readInt(endianness)
    }

    val float32: ValueCodec<Float> = object : ValueCodec<Float> {
        override val fixedSize = 4
        override fun encode(value: Float, writer: ByteWriter, endianness: Endianness) = writer.writeFloat(value, endianness)
        override fun decode(reader: ByteReader, endianness: Endianness): Float = reader.readFloat(endianness)
    }

    fun bytes(length: Int? = null): ValueCodec<ByteArray> = object : ValueCodec<ByteArray> {
        override val fixedSize = length
        override fun encode(value: ByteArray, writer: ByteWriter, endianness: Endianness) {
            require(length == null || value.size == length) { "Expected $length bytes, got ${value.size}" }
            writer.writeBytes(value)
        }
        override fun decode(reader: ByteReader, endianness: Endianness): ByteArray =
            reader.readBytes(length ?: reader.remaining)
    }

    fun string(length: Int, charset: Charset = Charsets.UTF_8): ValueCodec<String> = object : ValueCodec<String> {
        override val fixedSize = length
        override fun encode(value: String, writer: ByteWriter, endianness: Endianness) {
            val encoded = value.toByteArray(charset)
            require(encoded.size <= length) { "String is longer than $length bytes" }
            writer.writeBytes(encoded)
            repeat(length - encoded.size) { writer.writeByte(0) }
        }
        override fun decode(reader: ByteReader, endianness: Endianness): String =
            reader.readBytes(length).toString(charset).trimEnd('\u0000')
    }

    fun enum(names: Map<Int, String>): ValueCodec<EnumValue> = object : ValueCodec<EnumValue> {
        override val fixedSize = 1
        override fun encode(value: EnumValue, writer: ByteWriter, endianness: Endianness) {
            require(names.containsKey(value.raw)) { "Unknown enum value: ${value.raw}" }
            uint8.encode(value.raw, writer, endianness)
        }
        override fun decode(reader: ByteReader, endianness: Endianness): EnumValue {
            val raw = uint8.decode(reader, endianness)
            return EnumValue(raw, names[raw])
        }
    }

    fun bitField(mask: Int, shift: Int = Integer.numberOfTrailingZeros(mask)): ValueCodec<Int> = object : ValueCodec<Int> {
        init { require(mask in 1..0xff && shift in 0..7) }
        override val fixedSize = 1
        override fun encode(value: Int, writer: ByteWriter, endianness: Endianness) {
            require(value >= 0 && (value shl shift) and mask == (value shl shift)) {
                "BitField value out of range: $value"
            }
            uint8.encode((value shl shift) and mask, writer, endianness)
        }
        override fun decode(reader: ByteReader, endianness: Endianness): Int =
            (uint8.decode(reader, endianness) and mask) ushr shift
    }

    /** Reads a byte slice from a fixed-width field, padding the prefix on encode. */
    fun slice(offset: Int, length: Int): ValueCodec<ByteArray> = object : ValueCodec<ByteArray> {
        init { require(offset >= 0 && length >= 0) }
        override val fixedSize = offset + length
        override fun encode(value: ByteArray, writer: ByteWriter, endianness: Endianness) {
            require(value.size == length) { "Expected $length bytes, got ${value.size}" }
            repeat(offset) { writer.writeByte(0) }
            writer.writeBytes(value)
        }
        override fun decode(reader: ByteReader, endianness: Endianness): ByteArray {
            reader.readBytes(offset)
            return reader.readBytes(length)
        }
    }

    fun concat(vararg codecs: ValueCodec<ByteArray>): ValueCodec<ByteArray> = object : ValueCodec<ByteArray> {
        override val fixedSize = if (codecs.all { it.fixedSize != null }) codecs.sumOf { it.fixedSize!! } else null
        override fun encode(value: ByteArray, writer: ByteWriter, endianness: Endianness) {
            var offset = 0
            codecs.forEach { codec ->
                val size = codec.fixedSize ?: (value.size - offset)
                require(offset + size <= value.size) { "Not enough bytes for concatenated codec" }
                codec.encode(value.copyOfRange(offset, offset + size), writer, endianness)
                offset += size
            }
            require(offset == value.size) { "Unused bytes in concatenated codec" }
        }
        override fun decode(reader: ByteReader, endianness: Endianness): ByteArray {
            val output = ByteWriter()
            codecs.forEachIndexed { index, codec ->
                val size = codec.fixedSize ?: run {
                    require(index == codecs.lastIndex) { "Variable codec must be last in concat" }
                    reader.remaining
                }
                output.writeBytes(reader.readBytes(size))
            }
            return output.toByteArray()
        }
    }
}

/** Naming alias that reads naturally in protocol definitions. */
object ProtocolPrimitive {
    val UInt8: ValueCodec<Int> get() = ProtocolPrimitives.uint8
    val Int8: ValueCodec<Int> get() = ProtocolPrimitives.int8
    val UInt16: ValueCodec<Int> get() = ProtocolPrimitives.uint16
    val Int16: ValueCodec<Int> get() = ProtocolPrimitives.int16
    val UInt32: ValueCodec<Long> get() = ProtocolPrimitives.uint32
    val Int32: ValueCodec<Int> get() = ProtocolPrimitives.int32
    val Float32: ValueCodec<Float> get() = ProtocolPrimitives.float32
}

data class EnumValue(val raw: Int, val name: String? = null)

package com.Fusion.Btremix.protocol.api

interface Checksum {
    val widthBits: Int

    fun calculate(data: ByteArray): Int

    fun compute(data: ByteArray): Int = calculate(data)

    fun verify(data: ByteArray, expected: Int): Boolean = calculate(data) == expected
}

class Sum8 : Checksum {
    override val widthBits = 8
    override fun calculate(data: ByteArray): Int = data.fold(0) { sum, byte -> (sum + (byte.toInt() and 0xff)) and 0xff }
}

class Xor8 : Checksum {
    override val widthBits = 8
    override fun calculate(data: ByteArray): Int = data.fold(0) { value, byte -> value xor (byte.toInt() and 0xff) }
}

/** CRC-8 with the common poly=0x07, init=0, non-reflected parameters. */
class Crc8(
    private val polynomial: Int = 0x07,
    private val initial: Int = 0,
    private val xorOut: Int = 0,
) : Checksum {
    override val widthBits = 8

    override fun calculate(data: ByteArray): Int {
        var crc = initial and 0xff
        data.forEach { byte ->
            crc = crc xor (byte.toInt() and 0xff)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor polynomial) and 0xff else (crc shl 1) and 0xff }
        }
        return (crc xor xorOut) and 0xff
    }
}

/** CRC-16/XMODEM parameters by default: poly=0x1021 and init=0. */
class Crc16(
    private val polynomial: Int = 0x1021,
    private val initial: Int = 0,
    private val xorOut: Int = 0,
) : Checksum {
    override val widthBits = 16

    override fun calculate(data: ByteArray): Int {
        var crc = initial and 0xffff
        data.forEach { byte ->
            crc = crc xor ((byte.toInt() and 0xff) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor polynomial) and 0xffff else (crc shl 1) and 0xffff }
        }
        return (crc xor xorOut) and 0xffff
    }
}

typealias CRC8 = Crc8
typealias CRC16 = Crc16

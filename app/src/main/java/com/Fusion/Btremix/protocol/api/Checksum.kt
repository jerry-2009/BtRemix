package com.Fusion.Btremix.protocol.api

/**
 * A checksum implementation created from a normalized [ChecksumSpec].
 *
 * Protocol codecs depend on this contract rather than on an algorithm enum, so adding another
 * registered checksum function does not require editing the codec's algorithm dispatch.
 */
interface Checksum {
    /** Width of the value in bits. A disabled checksum has width zero. */
    val widthBits: Int

    fun calculate(data: ByteArray): Int

    fun compute(data: ByteArray): Int = calculate(data)

    fun verify(data: ByteArray, expected: Int): Boolean = calculate(data) == expected
}

/** Legacy checksum names retained only for source-compatible callers and old package adapters. */
enum class ChecksumAlgorithm { NONE, SUM8, XOR8 }

/** Checksum coverage supported by the P1 framed codec. */
enum class ChecksumCoverage { BODY }

/**
 * Vendor-neutral, serializable description of a checksum.
 *
 * [function] is looked up in [ChecksumRegistry]. CRC parameters are normalized by the registry;
 * built-in non-CRC functions use their fixed parameters and width. [outputByteOrder] controls how
 * widths above eight bits are appended to and read from the wire. P1 supports body coverage only;
 * checksum placement and multi-segment coverage belong to the P2 frame model.
 */
data class ChecksumSpec(
    val function: String,
    val width: Int,
    val polynomial: Int? = null,
    val initial: Int = 0,
    val reflectIn: Boolean = false,
    val reflectOut: Boolean = false,
    val xorOut: Int = 0,
    val outputByteOrder: Endianness = Endianness.BIG,
    val coverage: ChecksumCoverage = ChecksumCoverage.BODY,
) {
    /** Source-compatible adapter for code that still constructs the old enum form. */
    constructor(
        algorithm: ChecksumAlgorithm,
        coverage: ChecksumCoverage = ChecksumCoverage.BODY,
    ) : this(
        function = algorithm.name.lowercase(),
        width = if (algorithm == ChecksumAlgorithm.NONE) 0 else 8,
        coverage = coverage,
    )

    /** Canonical form used by registries and diagnostics. */
    fun normalized(): ChecksumSpec = copy(function = function.trim().lowercase())

    companion object {
        fun legacy(name: String): ChecksumSpec = when (name.trim().lowercase()) {
            "none" -> ChecksumSpec(ChecksumAlgorithm.NONE)
            "sum8" -> ChecksumSpec(ChecksumAlgorithm.SUM8)
            "xor8" -> ChecksumSpec(ChecksumAlgorithm.XOR8)
            else -> throw IllegalArgumentException(
                "unsupported legacy checksum '$name'; supported: none, sum8, xor8",
            )
        }
    }
}

/** One parameter validation failure, with the field name suitable for a Definition path report. */
data class ChecksumValidationError(
    val parameter: String,
    val message: String,
)

fun interface ChecksumFactory {
    fun create(spec: ChecksumSpec): Checksum
}

class Sum8 : Checksum {
    override val widthBits = 8
    override fun calculate(data: ByteArray): Int =
        data.fold(0) { sum, byte -> (sum + (byte.toInt() and 0xff)) and 0xff }
}

class Xor8 : Checksum {
    override val widthBits = 8
    override fun calculate(data: ByteArray): Int =
        data.fold(0) { value, byte -> value xor (byte.toInt() and 0xff) }
}

/** CRC-8 with configurable Rocksoft-style initial/reflection/final parameters. */
class Crc8(
    private val polynomial: Int = 0x07,
    private val initial: Int = 0,
    private val xorOut: Int = 0,
    private val reflectIn: Boolean = false,
    private val reflectOut: Boolean = false,
) : Checksum {
    override val widthBits = 8
    private val delegate = ParameterizedCrc(
        ChecksumSpec(
            function = "crc",
            width = 8,
            polynomial = polynomial,
            initial = initial,
            reflectIn = reflectIn,
            reflectOut = reflectOut,
            xorOut = xorOut,
        ),
    )

    override fun calculate(data: ByteArray): Int = delegate.calculate(data)
}

/** CRC-16/XMODEM-compatible defaults with configurable reflection and final parameters. */
class Crc16(
    private val polynomial: Int = 0x1021,
    private val initial: Int = 0,
    private val xorOut: Int = 0,
    private val reflectIn: Boolean = false,
    private val reflectOut: Boolean = false,
) : Checksum {
    override val widthBits = 16
    private val delegate = ParameterizedCrc(
        ChecksumSpec(
            function = "crc",
            width = 16,
            polynomial = polynomial,
            initial = initial,
            reflectIn = reflectIn,
            reflectOut = reflectOut,
            xorOut = xorOut,
        ),
    )

    override fun calculate(data: ByteArray): Int = delegate.calculate(data)
}

internal class ParameterizedCrc(private val spec: ChecksumSpec) : Checksum {
    override val widthBits: Int = spec.width

    override fun calculate(data: ByteArray): Int {
        val mask = (1 shl widthBits) - 1
        val topBit = 1 shl (widthBits - 1)
        val polynomial = requireNotNull(spec.polynomial) and mask
        var crc = spec.initial and mask
        data.forEach { raw ->
            val byte = if (spec.reflectIn) reverseBits(raw.toInt() and 0xff, 8) else raw.toInt() and 0xff
            crc = crc xor (byte shl (widthBits - 8))
            repeat(8) {
                crc = if (crc and topBit != 0) {
                    ((crc shl 1) xor polynomial) and mask
                } else {
                    (crc shl 1) and mask
                }
            }
        }
        if (spec.reflectOut) crc = reverseBits(crc, widthBits)
        return (crc xor spec.xorOut) and mask
    }
}

private fun reverseBits(value: Int, width: Int): Int {
    var source = value
    var result = 0
    repeat(width) {
        result = (result shl 1) or (source and 1)
        source = source ushr 1
    }
    return result
}

typealias CRC8 = Crc8
typealias CRC16 = Crc16

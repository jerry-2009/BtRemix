package com.Fusion.Btremix.protocol.api

/**
 * The structural role of one consecutive span in a wire frame.
 *
 * Roles describe bytes only. They never execute protocol logic or imply a vendor command set.
 */
enum class FrameSegmentRole {
    MAGIC,
    CONSTANT,
    COMMAND,
    SEQUENCE,
    LENGTH,
    OPAQUE,
    PAYLOAD,
    CHECKSUM,
}

/**
 * One consecutive, non-overlapping frame segment.
 *
 * [width] is mandatory for every fixed-width role. The payload is the only variable-width segment
 * and therefore has no width. [constant] holds the exact bytes for magic/constant segments.
 * [includeInLength] and [includeInChecksum] opt this segment into the two aggregate spans.
 */
data class FrameSegmentSpec(
    val role: FrameSegmentRole,
    val width: Int? = null,
    val order: Endianness = Endianness.BIG,
    val name: String? = null,
    val constant: ByteArray? = null,
    val includeInLength: Boolean = false,
    val includeInChecksum: Boolean = false,
) {
    fun copyConstant(): ByteArray? = constant?.clone()

    override fun equals(other: Any?): Boolean = other is FrameSegmentSpec &&
        role == other.role &&
        width == other.width &&
        order == other.order &&
        name == other.name &&
        constant.contentEquals(other.constant) &&
        includeInLength == other.includeInLength &&
        includeInChecksum == other.includeInChecksum

    override fun hashCode(): Int {
        var result = role.hashCode()
        result = 31 * result + (width ?: 0)
        result = 31 * result + order.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (constant?.contentHashCode() ?: 0)
        result = 31 * result + includeInLength.hashCode()
        result = 31 * result + includeInChecksum.hashCode()
        return result
    }
}

/** Ordered segment table for one direction of a segmented frame protocol. */
data class FrameLayoutSpec(val segments: List<FrameSegmentSpec>) {
    fun segment(role: FrameSegmentRole): FrameSegmentSpec? = segments.singleOrNull { it.role == role }

    val commandWidthBytes: Int? get() = segment(FrameSegmentRole.COMMAND)?.width

    fun minimalBytes(checksumWidth: Int): Int = resolvedOffsets(0, checksumWidth).lastOffset()

    internal fun resolvedOffsets(payloadWidth: Int?, checksumWidth: Int): ResolvedFrameLayout =
        ResolvedFrameLayout(this, payloadWidth, checksumWidth)
}

/** A pair of direction-specific layouts; responses may carry fields requests do not. */
data class SegmentedFramingSpec(
    val request: FrameLayoutSpec,
    val response: FrameLayoutSpec,
)

data class FrameLayoutIssue(val segmentIndex: Int?, val parameter: String, val message: String)

/**
 * Structural validation shared by the JSON validator and both segmented runtimes.
 *
 * The checks here are intentionally about byte layout only. Command/matcher reachability is
 * validated by the Definition layer, where transaction declarations are available.
 */
object FrameLayoutRules {
    fun validate(
        layout: FrameLayoutSpec,
        checksumWidth: Int,
        checksumOrder: Endianness? = null,
    ): List<FrameLayoutIssue> = buildList {
        if (layout.segments.isEmpty()) {
            add(FrameLayoutIssue(null, "segments", "must contain at least one segment"))
            return@buildList
        }

        val roles = layout.segments.groupingBy { it.role }.eachCount()
        roles.filter { (role, count) ->
            count > 1 && role !in setOf(FrameSegmentRole.CONSTANT, FrameSegmentRole.OPAQUE)
        }.keys.forEach { role ->
            add(FrameLayoutIssue(null, role.jsonName, "must appear at most once"))
        }
        if (layout.segments.first().role != FrameSegmentRole.MAGIC) {
            add(FrameLayoutIssue(0, "segments[0].role", "must be 'magic' so a stream can resynchronise"))
        }
        if (roles[FrameSegmentRole.COMMAND] != 1) {
            add(FrameLayoutIssue(null, "segments", "must contain exactly one command segment"))
        }
        if (roles[FrameSegmentRole.PAYLOAD] != 1) {
            add(FrameLayoutIssue(null, "segments", "must contain exactly one payload segment"))
        }
        if (roles[FrameSegmentRole.LENGTH] != 1) {
            add(FrameLayoutIssue(null, "segments", "must contain exactly one length segment"))
        }
        if (checksumWidth > 0 && roles[FrameSegmentRole.CHECKSUM] != 1) {
            add(FrameLayoutIssue(null, "segments", "must contain exactly one checksum segment"))
        }
        if (checksumWidth == 0 && roles.containsKey(FrameSegmentRole.CHECKSUM)) {
            add(FrameLayoutIssue(null, "segments", "checksum segment requires a checksum declaration"))
        }

        layout.segments.forEachIndexed { index, segment ->
            val path = "segments[$index]"
            when (segment.role) {
                FrameSegmentRole.PAYLOAD -> {
                    if (segment.width != null) add(FrameLayoutIssue(index, "$path.width", "must be omitted for payload"))
                    if (segment.constant != null) add(FrameLayoutIssue(index, "$path.constant", "is not valid for payload"))
                }

                FrameSegmentRole.COMMAND -> {
                    if (segment.width !in 1..3) {
                        add(FrameLayoutIssue(index, "$path.width", "must be 1, 2 or 3 bytes for a 24-bit command"))
                    }
                }

                FrameSegmentRole.SEQUENCE -> {
                    if (segment.width != 1) add(FrameLayoutIssue(index, "$path.width", "must be 1 byte"))
                }

                FrameSegmentRole.LENGTH -> {
                    if (segment.width !in 1..4) {
                        add(FrameLayoutIssue(index, "$path.width", "must be 1..4 bytes"))
                    }
                    if (segment.includeInLength) {
                        add(FrameLayoutIssue(index, "$path.includeInLength", "a length field cannot include itself"))
                    }
                }

                FrameSegmentRole.CHECKSUM -> {
                    if (checksumWidth > 0 && segment.width != checksumWidth) {
                        add(
                            FrameLayoutIssue(
                                index,
                                "$path.width",
                                "must equal the declared checksum width ($checksumWidth bytes)",
                            ),
                        )
                    }
                    if (checksumOrder != null && segment.order != checksumOrder) {
                        add(
                            FrameLayoutIssue(
                                index,
                                "$path.order",
                                "must match checksum outputByteOrder '${checksumOrder.name.lowercase()}'",
                            ),
                        )
                    }
                    if (segment.includeInChecksum) {
                        add(FrameLayoutIssue(index, "$path.includeInChecksum", "a checksum cannot cover itself"))
                    }
                }

                FrameSegmentRole.MAGIC,
                FrameSegmentRole.CONSTANT,
                -> {
                    if (segment.width == null || segment.width <= 0) {
                        add(FrameLayoutIssue(index, "$path.width", "must be positive"))
                    }
                    if (segment.constant == null) {
                        add(FrameLayoutIssue(index, "$path.constant", "is required for ${segment.role.jsonName}"))
                    } else if (segment.width != segment.constant.size) {
                        add(FrameLayoutIssue(index, "$path.constant", "length must equal segment width ${segment.width}"))
                    }
                }

                FrameSegmentRole.OPAQUE -> {
                    if (segment.width == null || segment.width <= 0) {
                        add(FrameLayoutIssue(index, "$path.width", "must be positive"))
                    }
                    if (segment.constant != null) {
                        add(FrameLayoutIssue(index, "$path.constant", "is not valid for opaque bytes"))
                    }
                }
            }
        }

        val payload = layout.segment(FrameSegmentRole.PAYLOAD)
        if (payload?.includeInLength != true) {
            add(FrameLayoutIssue(null, "segments", "payload must participate in the length field"))
        }
        validateContiguousCoverage(
            layout,
            includeInLength = true,
            parameter = "includeInLength",
            errors = this,
        )
        if (checksumWidth > 0) {
            validateContiguousCoverage(
                layout,
                includeInChecksum = true,
                parameter = "includeInChecksum",
                errors = this,
            )
        } else if (layout.segments.any { it.includeInChecksum }) {
            add(
                FrameLayoutIssue(
                    null,
                    "segments",
                    "includeInChecksum requires a checksum declaration",
                ),
            )
        }

        val commandIndex = layout.segments.indexOfFirst { it.role == FrameSegmentRole.COMMAND }
        val lengthIndex = layout.segments.indexOfFirst { it.role == FrameSegmentRole.LENGTH }
        val payloadIndex = layout.segments.indexOfFirst { it.role == FrameSegmentRole.PAYLOAD }
        if (lengthIndex >= payloadIndex && payloadIndex >= 0) {
            add(FrameLayoutIssue(lengthIndex, "segments[$lengthIndex].role", "length must precede payload"))
        }
        if (commandIndex >= payloadIndex && payloadIndex >= 0) {
            add(FrameLayoutIssue(commandIndex, "segments[$commandIndex].role", "command must precede payload"))
        }
    }

    private fun validateContiguousCoverage(
        layout: FrameLayoutSpec,
        includeInLength: Boolean = false,
        includeInChecksum: Boolean = false,
        parameter: String,
        errors: MutableList<FrameLayoutIssue>,
    ) {
        val selected = layout.segments.indices.filter { index ->
            val segment = layout.segments[index]
            if (includeInLength) segment.includeInLength else segment.includeInChecksum
        }
        if (selected.isEmpty()) {
            errors += FrameLayoutIssue(null, "segments", "$parameter must select at least one segment")
            return
        }
        if (selected.last() - selected.first() + 1 != selected.size) {
            errors += FrameLayoutIssue(
                selected.first(),
                "segments[${selected.first()}].$parameter",
                "selected segments must be contiguous",
            )
        }
    }
}

internal class ResolvedFrameLayout(
    private val layout: FrameLayoutSpec,
    payloadWidth: Int?,
    checksumWidth: Int,
) {
    private val widths = layout.segments.map { segment ->
        when (segment.role) {
            FrameSegmentRole.PAYLOAD -> requireNotNull(payloadWidth) {
                "Payload width is not known until the length field is decoded"
            }

            FrameSegmentRole.CHECKSUM -> checksumWidth
            else -> requireNotNull(segment.width) { "Segment ${segment.role} requires width" }
        }
    }
    private val offsets = widths.runningFold(0, Int::plus)

    val totalBytes: Int get() = offsets.last()

    operator fun get(role: FrameSegmentRole): Int? =
        layout.segments.indexOfFirst { it.role == role }.takeIf { it >= 0 }?.let(offsets::get)

    fun width(index: Int): Int = widths[index]

    fun span(index: Int): IntRange {
        val start = offsets[index]
        return start until start + widths[index]
    }

    fun countedFixedBytes(includeInLength: Boolean): Int =
        layout.segments.indices.sumOf { index ->
            val segment = layout.segments[index]
            if (segment.role == FrameSegmentRole.PAYLOAD) {
                0
            } else if (if (includeInLength) segment.includeInLength else segment.includeInChecksum) {
                widths[index]
            } else {
                0
            }
        }

    fun lastOffset(): Int = totalBytes
}

val FrameSegmentRole.jsonName: String
    get() = name.lowercase()

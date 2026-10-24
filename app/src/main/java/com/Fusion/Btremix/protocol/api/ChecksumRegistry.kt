package com.Fusion.Btremix.protocol.api

/**
 * Process-wide registry for generic checksum functions.
 *
 * Entries validate their own parameter set and return a parser-independent [Checksum]. The
 * available-function list is included in unknown-function diagnostics.
 */
object ChecksumRegistry {
    private data class Entry(
        val factory: (ChecksumSpec) -> Checksum,
        val validate: (ChecksumSpec) -> List<ChecksumValidationError>,
    )

    private val entries = linkedMapOf(
        "none" to Entry(
            factory = {
                object : Checksum {
                    override val widthBits = 0
                    override fun calculate(data: ByteArray): Int = 0
                }
            },
            validate = { spec ->
                if (spec.width != 0) listOf(ChecksumValidationError("width", "must be 0 for function 'none'"))
                else emptyList()
            },
        ),
        "sum8" to Entry(
            factory = { Sum8() },
            validate = { spec -> fixedWidth(spec, 8) },
        ),
        "xor8" to Entry(
            factory = { Xor8() },
            validate = { spec -> fixedWidth(spec, 8) },
        ),
        "crc" to Entry(
            factory = { spec -> ParameterizedCrc(spec) },
            validate = ::validateCrc,
        ),
    )

    @Synchronized
    fun register(
        function: String,
        factory: ChecksumFactory,
        validate: (ChecksumSpec) -> List<ChecksumValidationError> = { emptyList() },
    ) {
        val name = function.trim().lowercase()
        require(name.matches(Regex("[a-z][a-z0-9._-]*"))) {
            "checksum function name '$function' is invalid"
        }
        require(name !in entries) { "checksum function '$name' is already registered" }
        entries[name] = Entry(factory::create, validate)
    }

    val functions: Set<String>
        @Synchronized get() = entries.keys.toSet()

    @Synchronized
    fun validate(spec: ChecksumSpec): List<ChecksumValidationError> {
        val normalized = spec.normalized()
        if (normalized.function.isBlank()) {
            return listOf(ChecksumValidationError("function", "must not be blank"))
        }
        val entry = entries[normalized.function] ?: return listOf(
            ChecksumValidationError(
                "function",
                "unsupported checksum function '${normalized.function}'; available: ${functions.sorted()}",
            ),
        )
        return entry.validate(normalized)
    }

    /** Creates a checksum or throws a diagnostic containing concept, function and available names. */
    @Synchronized
    fun create(spec: ChecksumSpec): Checksum {
        val normalized = spec.normalized()
        val errors = validate(normalized)
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(
                "invalid checksum '${normalized.function}': ${errors.joinToString("; ") { "${it.parameter}: ${it.message}" }}",
            )
        }
        return requireNotNull(entries[normalized.function]).factory(normalized)
    }

    private fun fixedWidth(spec: ChecksumSpec, expected: Int): List<ChecksumValidationError> =
        if (spec.width != expected) {
            listOf(ChecksumValidationError("width", "must be $expected for function '${spec.function}'"))
        } else {
            emptyList()
        }

    private fun validateCrc(spec: ChecksumSpec): List<ChecksumValidationError> = buildList {
        if (spec.width !in setOf(8, 16)) {
            add(ChecksumValidationError("width", "must be 8 or 16 for function 'crc'"))
            return@buildList
        }
        val mask = (1 shl spec.width) - 1
        val polynomial = spec.polynomial
        if (polynomial == null || polynomial !in 1..mask) {
            add(ChecksumValidationError("polynomial", "must be a non-zero ${spec.width}-bit value"))
        }
        if (spec.initial !in 0..mask) {
            add(ChecksumValidationError("initial", "must fit ${spec.width} bits"))
        }
        if (spec.xorOut !in 0..mask) {
            add(ChecksumValidationError("xorOut", "must fit ${spec.width} bits"))
        }
    }
}

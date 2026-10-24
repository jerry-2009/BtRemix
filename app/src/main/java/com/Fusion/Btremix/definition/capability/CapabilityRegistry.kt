package com.Fusion.Btremix.definition.capability

import com.Fusion.Btremix.definition.api.DefinitionRuntimeApi

/** Declarative contract for one capability name a device Definition may require. */
data class CapabilityDescriptor(
    val id: String,
    val minRuntimeApi: Int,
    val parameterRule: String,
)

data class CapabilityValidationError(
    val index: Int,
    val message: String,
)

/**
 * Registry of runtime capabilities exposed to device Definitions.
 *
 * This is a load-time contract, not a dependency resolver. A package names capabilities in
 * `manifest.requires`; the validator checks each name and the minimum runtime API. P1 registers only
 * the checksum and delimited-splitter primitives that the runtime actually implements.
 */
object CapabilityRegistry {
    private val capabilities = linkedMapOf(
        "checksum.none" to CapabilityDescriptor(
            id = "checksum.none",
            minRuntimeApi = 1,
            parameterRule = "checksum.width must be 0",
        ),
        "checksum.sum8" to CapabilityDescriptor(
            id = "checksum.sum8",
            minRuntimeApi = 1,
            parameterRule = "checksum.width must be 8",
        ),
        "checksum.xor8" to CapabilityDescriptor(
            id = "checksum.xor8",
            minRuntimeApi = 1,
            parameterRule = "checksum.width must be 8",
        ),
        "checksum.crc" to CapabilityDescriptor(
            id = "checksum.crc",
            minRuntimeApi = 1,
            parameterRule = "width is 8 or 16; CRC parameters fit width; outputByteOrder is big or little",
        ),
        "splitter.delimited" to CapabilityDescriptor(
            id = "splitter.delimited",
            minRuntimeApi = 1,
            parameterRule = "header and trailer fit UInt8; maxFrameBytes is positive",
        ),
        "splitter.lengthPrefixed" to CapabilityDescriptor(
            id = "splitter.lengthPrefixed",
            minRuntimeApi = 1,
            parameterRule = "response layout declares magic and a 1..4-byte length; maxFrameBytes is positive",
        ),
        "framing.segmented" to CapabilityDescriptor(
            id = "framing.segmented",
            minRuntimeApi = 1,
            parameterRule = "request/response segment tables are ordered, contiguous and checksum-covered",
        ),
        "framing.command24" to CapabilityDescriptor(
            id = "framing.command24",
            minRuntimeApi = 1,
            parameterRule = "command segment width is 1..3 bytes",
        ),
    )

    val names: Set<String> get() = capabilities.keys.toSet()

    fun find(name: String): CapabilityDescriptor? = capabilities[name.trim()]

    /**
     * Validates the ordered `requires` values so duplicate and unsupported entries report their
     * original array index. Runtime API compatibility is validated separately.
     */
    fun validateRequires(requires: List<String>, runtimeApi: Int): List<CapabilityValidationError> {
        val errors = mutableListOf<CapabilityValidationError>()
        val seen = mutableSetOf<String>()
        requires.forEachIndexed { index, raw ->
            val name = raw.trim()
            when {
                name.isEmpty() ->
                    errors += CapabilityValidationError(index, "capability name must not be blank")
                !seen.add(name) ->
                    errors += CapabilityValidationError(index, "duplicate capability '$name'")
                else -> {
                    val descriptor = capabilities[name]
                    when {
                        descriptor == null ->
                            errors += CapabilityValidationError(
                                index,
                                "unsupported capability '$name'; available: ${names.sorted()}",
                            )
                        runtimeApi < descriptor.minRuntimeApi ->
                            errors += CapabilityValidationError(
                                index,
                                "capability '$name' requires runtimeApi ${descriptor.minRuntimeApi} " +
                                    "or newer, declared $runtimeApi",
                            )
                    }
                }
            }
        }
        return errors
    }

    fun validateRuntimeApi(runtimeApi: Int): String? =
        if (runtimeApi !in DefinitionRuntimeApi.SUPPORTED) {
            "unsupported runtime API $runtimeApi, supported ${DefinitionRuntimeApi.SUPPORTED}"
        } else {
            null
        }
}

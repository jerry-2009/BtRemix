package com.Fusion.Btremix.protocol.extension

/**
 * Explicit, immutable-by-convention registry for compiled first-party extensions.
 *
 * There is no classpath scan, reflection or package-driven loading. Duplicate IDs and future API
 * versions fail immediately so registration order can never decide which implementation wins.
 */
class ProtocolExtensionRegistry(initial: Iterable<ProtocolExtension> = emptyList()) {
    private val values = linkedMapOf<String, ProtocolExtension>()

    init {
        initial.forEach(::register)
    }

    /** Registers exactly one implementation. Re-registering an ID is always an error. */
    @Synchronized
    fun register(extension: ProtocolExtension): ProtocolExtension {
        val id = extension.id.trim()
        require(id.isNotEmpty()) { "Protocol extension id must not be blank" }
        require(id == extension.id) { "Protocol extension id must not contain surrounding whitespace" }
        require(extension.apiVersion in 1..ProtocolExtension.CURRENT_API_VERSION) {
            "Protocol extension '$id' uses unsupported apiVersion ${extension.apiVersion}; " +
                "supported 1..${ProtocolExtension.CURRENT_API_VERSION}"
        }
        require(extension.entries.isNotEmpty()) { "Protocol extension '$id' must declare at least one entry" }
        require(extension.entries.all { it.isNotBlank() && it == it.trim() }) {
            "Protocol extension '$id' entries must be non-blank and contain no surrounding whitespace"
        }
        require(id !in values) { "Duplicate protocol extension id '$id'" }
        values[id] = extension
        return extension
    }

    /** Returns one explicitly registered implementation, or null when it was not registered. */
    fun find(id: String): ProtocolExtension? = values[id.trim()]

    /** Stable snapshot used by diagnostics and architecture tests. */
    fun all(): List<ProtocolExtension> = values.values.sortedBy { it.id }

    /** Number of explicitly registered implementations. */
    val size: Int get() = values.size
}

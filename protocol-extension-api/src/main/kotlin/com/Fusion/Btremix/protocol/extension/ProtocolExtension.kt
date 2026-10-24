package com.Fusion.Btremix.protocol.extension

import kotlin.time.Duration

/** Stable failure boundary crossing from a host extension invocation back into the script runtime. */
open class ProtocolExtensionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Compatibility contract for first-party protocol extensions.
 *
 * Version 1 defines only bounded byte transforms and controlled protocol I/O. Extensions are
 * compiled into the application and registered explicitly by the composition root. A `.dcpkg`
 * may name an extension, API version and entry, but it never carries executable code.
 *
 * Compatibility rules:
 * - additive methods and result types require a new [CURRENT_API_VERSION];
 * - a v1 implementation keeps the same ID and entry semantics;
 * - definitions may require only a registered implementation with exactly the declared version;
 * - future API versions are rejected before [create] is called.
 */
interface ProtocolExtension {
    /** Stable, vendor-neutral or vendor-isolated identifier referenced by a Definition manifest. */
    val id: String

    /** API contract implemented by this extension. Version 1 is [CURRENT_API_VERSION]. */
    val apiVersion: Int

    /** Entries this extension implements. Definitions may not invent arbitrary entry names. */
    val entries: Set<String>

    /** Creates one session-scoped instance. The runtime owns and closes every instance. */
    fun create(runtime: ExtensionRuntime): ProtocolExtensionInstance

    companion object {
        const val CURRENT_API_VERSION: Int = 1
    }
}

/**
 * Pure-Kotlin host services available while one extension instance is created.
 *
 * This deliberately contains no session, transport, Android Context, Bluetooth handle, UI or
 * lifecycle object.
 */
data class ExtensionRuntime(
    val apiVersion: Int = ProtocolExtension.CURRENT_API_VERSION,
)

/** One execution artifact owned by a host session. */
interface ProtocolExtensionInstance : AutoCloseable {
    /**
     * Executes one declared entry.
     *
     * [input] and every I/O payload are defensive copies. The host enforces timeout and byte
     * limits around this call; extensions must not start independent connection ownership.
     */
    suspend fun execute(
        entry: String,
        input: ByteArray,
        io: ControlledProtocolIo,
    ): ExtensionResult

    /** Called once when the host session closes. Default implementations need no cleanup. */
    override fun close() = Unit
}

/** Bounded result of one extension invocation. */
sealed interface ExtensionResult {
    /** Optional transformed bytes returned to the JSON program. */
    data class Success(val output: ByteArray = byteArrayOf()) : ExtensionResult {
        override fun equals(other: Any?): Boolean =
            other is Success && output.contentEquals(other.output)

        override fun hashCode(): Int = output.contentHashCode()
    }

    /** Describable failure; the host converts this to a session-visible runtime error. */
    data class Failure(
        val code: String,
        val message: String,
        val cause: Throwable? = null,
    ) : ExtensionResult {
        init {
            require(code.isNotBlank()) { "Extension failure code must not be blank" }
            require(message.isNotBlank()) { "Extension failure message must not be blank" }
        }
    }
}

/** Read-only view of Definition endpoint names granted to an extension. */
interface ControlledProtocolIo {
    val endpoints: Set<String>

    /** Sends already-encoded bytes through the session's serialized write path. */
    suspend fun send(bytes: ByteArray)

    /**
     * Sends one declared endpoint with an optional payload override.
     *
     * The host, not the extension, selects framing, writes the transport and owns retries.
     */
    suspend fun send(endpoint: String, payload: ByteArray? = null)

    /** Executes one declared request/response endpoint with bounded host-owned matching. */
    suspend fun request(request: ExtensionRequest): ExtensionResponse

    /** Waits for one data-only inbound frame selected by a host-owned matcher. */
    suspend fun waitFor(matcher: ExtensionFrameMatcher, timeout: Duration): ExtensionFrame
}

/** One controlled request; endpoint names cannot escape `protocol.transactions`. */
data class ExtensionRequest(
    val endpoint: String,
    val payload: ByteArray? = null,
    val timeout: Duration? = null,
    val retries: Int? = null,
) {
    init {
        require(endpoint.isNotBlank()) { "Extension request endpoint must not be blank" }
        require(timeout == null || timeout.isPositive()) { "Extension timeout must be positive" }
        require(retries == null || retries >= 0) { "Extension retries cannot be negative" }
    }
}

/** Decoded response returned through controlled I/O. */
data class ExtensionResponse(
    val command: Int,
    val sequence: Int?,
    val payload: ByteArray,
)

/** Data-only inbound matcher; it cannot reference lifecycle or transport state. */
data class ExtensionFrameMatcher(
    val command: Int? = null,
    val sequence: Int? = null,
    val payloadPrefix: ByteArray = byteArrayOf(),
    val payloadTypes: Set<Int> = emptySet(),
)

/** Decoded inbound frame returned through controlled I/O. */
data class ExtensionFrame(
    val command: Int,
    val sequence: Int?,
    val payload: ByteArray,
)

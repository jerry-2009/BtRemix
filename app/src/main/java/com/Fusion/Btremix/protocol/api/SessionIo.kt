package com.Fusion.Btremix.protocol.api

import kotlin.time.Duration

/** Cancels one frame subscription without exposing its coroutine or transport. */
fun interface SessionSubscription {
    fun cancel()
}

/**
 * A transaction selected by a stable, Definition-declared endpoint name.
 *
 * Scripts never receive a transport handle. The Definition adapter resolves [endpoint] to an
 * existing `protocol.transactions` entry and applies [matcher] / [timeout] as call-site overrides.
 */
data class SessionRequest(
    val endpoint: String,
    /** Optional request built by a higher layer; null uses the endpoint's Definition request. */
    val packet: Packet? = null,
    val matcher: PacketMatcherSpec? = null,
    val timeout: Duration? = null,
    val retries: Int? = null,
) {
    init {
        require(endpoint.isNotBlank()) { "Session request endpoint must not be blank" }
        require(timeout == null || timeout.isPositive()) { "Session request timeout must be positive" }
        require(retries == null || retries >= 0) { "Session request retries cannot be negative" }
    }
}

/**
 * Transport-neutral, host-controlled I/O granted to one Definition instance.
 *
 * Implementations own framing, read ownership, retries and write serialisation. Callers only deal
 * in decoded packets and declared endpoints; GATT handles, sockets and Android objects stay below
 * this boundary.
 */
interface SessionIo {
    /** Declared transaction names accepted by [request]. */
    val endpoints: Set<String>

    /** Writes one already-encoded frame through the session's serialised write path. */
    suspend fun send(bytes: ByteArray)

    /** Sends a Definition transaction packet once, without waiting for a response. */
    suspend fun send(request: SessionRequest)

    /** Runs a request/response transaction through the protocol runtime. */
    suspend fun request(request: SessionRequest): TransactionResult

    /** Waits for the first inbound packet matching [matcher]. */
    suspend fun waitFor(matcher: PacketMatcherSpec, timeout: Duration): Packet

    /** Registers a session-scoped frame handler. Cancelling more than once is harmless. */
    fun onFrame(matcher: PacketMatcherSpec, handler: suspend (Packet) -> Unit): SessionSubscription

    /** Cancels every subscription installed through this I/O instance. Idempotent. */
    suspend fun close()
}

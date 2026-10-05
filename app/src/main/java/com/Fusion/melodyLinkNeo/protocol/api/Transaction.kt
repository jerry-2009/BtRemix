package com.fusion.melodyLinkNeo.protocol.api

import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

interface ProtocolTransport {
    suspend fun write(data: ByteArray)

    fun notifications(): Flow<ByteArray>
}

interface SequenceGenerator {
    fun next(): Int
}

class IncrementingSequenceGenerator(start: Int = 0) : SequenceGenerator {
    private var value = (start - 1) and 0xff

    @Synchronized
    override fun next(): Int {
        value = (value + 1) and 0xff
        return value
    }
}

/**
 * Alternates between 0 and 1.
 *
 * Some byte-stream protocols expect the request sequence to toggle between two values rather than
 * increment; an incrementing counter would drift away from what the peer accepts.
 */
class TogglingSequenceGenerator(private var value: Int = 0) : SequenceGenerator {
    init {
        require(value in 0..1) { "Toggling sequence must start at 0 or 1" }
    }

    @Synchronized
    override fun next(): Int {
        val current = value
        value = 1 - value
        return current
    }
}

data class TransactionRequest(
    val request: Packet,
    val expectedCommand: Int? = null,
    val timeout: Duration? = null,
    val retries: Int? = null,
    val matcher: ((Packet) -> Boolean)? = null,
) {
    init {
        require(expectedCommand == null || expectedCommand in 0..0xff)
        require(timeout == null || timeout.isPositive()) { "Timeout must be positive" }
        require(retries == null || retries >= 0) { "Retries cannot be negative" }
    }
}

sealed interface TransactionResult {
    data class Success(val response: Packet, val attempts: Int) : TransactionResult
    data class Failure(val error: TransactionError) : TransactionResult
}

sealed interface TransactionError {
    val message: String

    data class Timeout(val timeout: Duration, val attempts: Int) : TransactionError {
        override val message = "Timed out after $timeout"
    }

    data class InvalidPacket(val cause: Throwable) : TransactionError {
        override val message = "Invalid packet: ${cause.message ?: cause::class.simpleName}"
    }

    data class WriteFailed(val cause: Throwable) : TransactionError {
        override val message = "Packet write failed: ${cause.message ?: cause::class.simpleName}"
    }

    data object Disconnected : TransactionError {
        override val message = "Transport notification stream closed"
    }

    data class RetryExhausted(val last: TransactionError, val attempts: Int) : TransactionError {
        override val message = "Retries exhausted after $attempts attempts: ${last.message}"
    }
}

class TransactionRuntime(
    private val transport: ProtocolTransport,
    private val packetEncoder: PacketEncoder,
    private val packetDecoder: PacketDecoder,
    private val defaultTimeout: Duration = Duration.parse("2s"),
    private val defaultRetries: Int = 0,
    private val sequenceGenerator: SequenceGenerator = IncrementingSequenceGenerator(),
) {
    init {
        require(defaultTimeout.isPositive()) { "Default timeout must be positive" }
        require(defaultRetries >= 0) { "Default retries cannot be negative" }
    }

    suspend fun execute(request: TransactionRequest): TransactionResult {
        val sequence = request.request.sequence ?: sequenceGenerator.next()
        val packet = request.request.copy(sequence = sequence)
        val timeout = request.timeout ?: defaultTimeout
        val retries = request.retries ?: defaultRetries
        val matcher = request.matcher ?: { response ->
            (request.expectedCommand == null || response.command == request.expectedCommand) &&
                (response.sequence == null || response.sequence == packet.sequence)
        }
        var lastError: TransactionError? = null

        repeat(retries + 1) { attemptIndex ->
            val attempt = attemptIndex + 1
            val result = runAttempt(packet, matcher, timeout, attempt)
            if (result is TransactionResult.Success) return result
            lastError = (result as TransactionResult.Failure).error
        }
        return TransactionResult.Failure(
            if (retries == 0) lastError!! else TransactionError.RetryExhausted(lastError!!, retries + 1),
        )
    }

    /** Writes [packet] once, assigning a sequence when the packet has none. */
    suspend fun send(packet: Packet) {
        val sequence = packet.sequence ?: sequenceGenerator.next()
        transport.write(packetEncoder.encode(packet.copy(sequence = sequence)))
    }

    private suspend fun runAttempt(
        packet: Packet,
        matcher: (Packet) -> Boolean,
        timeout: Duration,
        attempt: Int,
    ): TransactionResult = try {
        supervisorScope {
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(timeout) {
                    transport.notifications()
                        .map { bytes ->
                            try {
                                packetDecoder.decode(bytes)
                            } catch (error: Exception) {
                                throw InvalidPacketException(error)
                            }
                        }
                        .first(matcher)
                }
            }
            try {
                transport.write(packetEncoder.encode(packet))
                TransactionResult.Success(response.await(), attempt)
            } catch (error: InvalidPacketException) {
                TransactionResult.Failure(TransactionError.InvalidPacket(error.cause ?: error))
            } catch (error: NoSuchElementException) {
                TransactionResult.Failure(TransactionError.Disconnected)
            } catch (error: TimeoutCancellationException) {
                TransactionResult.Failure(TransactionError.Timeout(timeout, attempt))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                TransactionResult.Failure(TransactionError.WriteFailed(error))
            } finally {
                response.cancel()
            }
        }
    } catch (error: TimeoutCancellationException) {
        TransactionResult.Failure(TransactionError.Timeout(timeout, attempt))
    }

    private class InvalidPacketException(cause: Throwable) : IllegalArgumentException(cause.message, cause)
}

package com.Fusion.Btremix.protocol.api

/**
 * Protocol code is deliberately independent from Android and from Compose. A
 * runtime combines a packet codec with a transport when a request/response
 * transaction is needed.
 */
interface ProtocolRuntime {
    val packetEncoder: PacketEncoder
    val packetDecoder: PacketDecoder

    suspend fun execute(request: TransactionRequest): TransactionResult
}

/** A small production-facing implementation for packet transactions. */
class DefaultProtocolRuntime(
    private val transport: ProtocolTransport,
    override val packetEncoder: PacketEncoder,
    override val packetDecoder: PacketDecoder,
    timeout: kotlin.time.Duration = kotlin.time.Duration.parse("2s"),
    retries: Int = 0,
    sequenceGenerator: SequenceGenerator = IncrementingSequenceGenerator(),
) : ProtocolRuntime {
    private val transactionRuntime = TransactionRuntime(
        transport = transport,
        packetEncoder = packetEncoder,
        packetDecoder = packetDecoder,
        defaultTimeout = timeout,
        defaultRetries = retries,
        sequenceGenerator = sequenceGenerator,
    )

    override suspend fun execute(request: TransactionRequest): TransactionResult =
        transactionRuntime.execute(request)
}

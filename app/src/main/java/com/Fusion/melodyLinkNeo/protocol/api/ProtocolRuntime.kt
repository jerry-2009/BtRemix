package com.fusion.melodyLinkNeo.protocol.api

/**
 * Protocol code is deliberately independent from Android and from Compose. A
 * runtime combines a packet codec with a transport when a request/response
 * transaction is needed.
 */
interface ProtocolRuntime {
    val packetEncoder: PacketEncoder
    val packetDecoder: PacketDecoder

    suspend fun execute(request: TransactionRequest): TransactionResult

    /**
     * Writes [packet] once without waiting for a response.
     *
     * Many protocols acknowledge a SET command only at the framing layer and never answer it at the
     * application layer; declaring such a transaction as write-only keeps the action honest instead
     * of reporting a timeout for a command the device accepted.
     */
    suspend fun send(packet: Packet)
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

    override suspend fun send(packet: Packet) = transactionRuntime.send(packet)
}

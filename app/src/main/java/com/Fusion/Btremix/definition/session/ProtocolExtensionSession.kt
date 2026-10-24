package com.Fusion.Btremix.definition.session

import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.PacketMatcherSpec
import com.Fusion.Btremix.protocol.api.SessionIo
import com.Fusion.Btremix.protocol.api.SessionRequest
import com.Fusion.Btremix.protocol.api.TransactionResult
import com.Fusion.Btremix.protocol.extension.ControlledProtocolIo
import com.Fusion.Btremix.protocol.extension.ExtensionFrame
import com.Fusion.Btremix.protocol.extension.ExtensionFrameMatcher
import com.Fusion.Btremix.protocol.extension.ExtensionRequest
import com.Fusion.Btremix.protocol.extension.ExtensionResponse
import com.Fusion.Btremix.protocol.extension.ExtensionResult
import com.Fusion.Btremix.protocol.extension.ExtensionRuntime
import com.Fusion.Btremix.protocol.extension.ProtocolExtensionInstance
import com.Fusion.Btremix.protocol.extension.ProtocolExtensionRegistry
import com.Fusion.Btremix.protocol.extension.ProtocolExtensionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Session-scoped owner of declared first-party extension instances.
 *
 * Definitions can only select an explicitly registered implementation and entry. Instances receive
 * this class's narrow [ControlledProtocolIo] facade, never the owning Definition session, transport
 * or Android objects.
 */
class ProtocolExtensionSession internal constructor(
    private val definition: LoadedDeviceDefinition,
    registry: ProtocolExtensionRegistry,
    private val sessionIo: SessionIo,
) : AutoCloseable {
    private val instances = linkedMapOf<String, ProtocolExtensionInstance>()
    private val closed = AtomicBoolean(false)

    init {
        try {
            definition.manifest.extensions.forEach { declaration ->
                val extension = registry.find(declaration.id)
                    ?: throw ExtensionInvocationException(
                        "Extension '${declaration.id}' is not registered",
                    )
                if (extension.apiVersion != declaration.apiVersion) {
                    throw ExtensionInvocationException(
                        "Extension '${declaration.id}' API is ${extension.apiVersion}, " +
                            "definition declares ${declaration.apiVersion}",
                    )
                }
                if (declaration.entry !in extension.entries) {
                    throw ExtensionInvocationException(
                        "Extension '${declaration.id}' does not provide entry '${declaration.entry}'",
                    )
                }
                instances[declaration.id] = extension.create(
                    ExtensionRuntime(apiVersion = extension.apiVersion),
                )
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    internal suspend fun execute(
        extensionId: String,
        entry: String,
        input: ByteArray,
        timeout: Duration,
    ): ByteArray {
        check(!closed.get()) { "Protocol extension session is closed" }
        val declaration = definition.manifest.extensions.firstOrNull { it.id == extensionId }
            ?: throw ExtensionInvocationException("Extension '$extensionId' is not declared by this definition")
        if (entry != declaration.entry) {
            throw ExtensionInvocationException(
                "Extension '$extensionId' entry '$entry' does not match declared entry '${declaration.entry}'",
            )
        }
        require(input.size <= MAX_BYTES) {
            "Extension input is ${input.size} bytes; hard limit is $MAX_BYTES"
        }
        val instance = instances[extensionId]
            ?: throw ExtensionInvocationException("Extension '$extensionId' has no live instance")
        try {
            val result = withTimeout(timeout) {
                instance.execute(entry, input.clone(), ControlledIo())
            }
            return when (result) {
                is ExtensionResult.Success -> result.output.clone().also {
                    require(it.size <= MAX_BYTES) {
                        "Extension output is ${it.size} bytes; hard limit is $MAX_BYTES"
                    }
                }
                is ExtensionResult.Failure -> throw ExtensionInvocationException(
                    "Extension '$extensionId' failed (${result.code}): ${result.message}",
                    result.cause,
                )
            }
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                throw ExtensionInvocationException(
                    "Extension '$extensionId' timed out after ${timeout.inWholeMilliseconds} ms",
                    cancelled,
                )
            }
            throw cancelled
        } catch (error: ExtensionInvocationException) {
            throw error
        } catch (error: Throwable) {
            throw ExtensionInvocationException(
                "Extension '$extensionId' threw ${error::class.simpleName}: ${error.message ?: "no message"}",
                error,
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        instances.values.toList().asReversed().forEach { instance ->
            runCatching(instance::close)
        }
        instances.clear()
    }

    private inner class ControlledIo : ControlledProtocolIo {
        override val endpoints: Set<String> get() = sessionIo.endpoints

        override suspend fun send(bytes: ByteArray) {
            checkPayload(bytes)
            sessionIo.send(bytes.clone())
        }

        override suspend fun send(endpoint: String, payload: ByteArray?) {
            checkOpen()
            val transaction = transaction(endpoint)
            val packet = payload?.let {
                checkPayload(it)
                Packet(command = transaction.requestCommand, payload = it.clone())
            }
            sessionIo.send(SessionRequest(endpoint = endpoint, packet = packet))
        }

        override suspend fun request(request: ExtensionRequest): ExtensionResponse {
            checkOpen()
            val transaction = transaction(request.endpoint)
            val packet = request.payload?.let {
                checkPayload(it)
                Packet(command = transaction.requestCommand, payload = it.clone())
            }
            val result = sessionIo.request(
                SessionRequest(
                    endpoint = request.endpoint,
                    packet = packet,
                    timeout = request.timeout,
                    retries = request.retries,
                ),
            )
            return when (result) {
                is TransactionResult.Success -> ExtensionResponse(
                    command = result.response.command,
                    sequence = result.response.sequence,
                    payload = result.response.payload.clone(),
                )
                is TransactionResult.Failure -> throw ExtensionInvocationException(
                    "Controlled request '${request.endpoint}' failed: ${result.error.message}",
                    result.errorException(),
                )
            }
        }

        override suspend fun waitFor(
            matcher: ExtensionFrameMatcher,
            timeout: Duration,
        ): ExtensionFrame {
            checkOpen()
            val packet = sessionIo.waitFor(
                matcher = PacketMatcherSpec(
                    command = matcher.command,
                    sequence = matcher.sequence,
                    payloadPrefix = matcher.payloadPrefix.clone(),
                    payloadTypes = matcher.payloadTypes,
                ),
                timeout = timeout,
            )
            return ExtensionFrame(
                command = packet.command,
                sequence = packet.sequence,
                payload = packet.payload.clone(),
            )
        }

        private fun transaction(endpoint: String) =
            definition.protocol.transactions[endpoint]
                ?: throw ExtensionInvocationException(
                    "Controlled endpoint '$endpoint' is not declared; available: ${endpoints.sorted()}",
                )

        private fun checkPayload(payload: ByteArray) {
            require(payload.size <= MAX_BYTES) {
                "Controlled payload is ${payload.size} bytes; hard limit is $MAX_BYTES"
            }
        }

        private fun checkOpen() {
            check(!closed.get()) { "Protocol extension session is closed" }
        }
    }

    private companion object {
        const val MAX_BYTES = 4096
    }
}

internal class ExtensionInvocationException(
    message: String,
    cause: Throwable? = null,
) : ProtocolExtensionException(message, cause)

private fun TransactionResult.Failure.errorException(): Throwable {
    val cause = when (error) {
        is com.Fusion.Btremix.protocol.api.TransactionError.InvalidPacket -> error.cause
        is com.Fusion.Btremix.protocol.api.TransactionError.WriteFailed -> error.cause
        is com.Fusion.Btremix.protocol.api.TransactionError.RetryExhausted ->
            (error.last as? com.Fusion.Btremix.protocol.api.TransactionError.InvalidPacket)?.cause
                ?: (error.last as? com.Fusion.Btremix.protocol.api.TransactionError.WriteFailed)?.cause
        else -> null
    }
    return IllegalStateException(error.message, cause)
}

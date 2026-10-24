package com.Fusion.Btremix.vendor.testbed

import com.Fusion.Btremix.protocol.extension.ControlledProtocolIo
import com.Fusion.Btremix.protocol.extension.ExtensionResult
import com.Fusion.Btremix.protocol.extension.ExtensionRuntime
import com.Fusion.Btremix.protocol.extension.ProtocolExtension
import com.Fusion.Btremix.protocol.extension.ProtocolExtensionInstance

/**
 * Deterministic LabLink reference extension.
 *
 * This is deliberately a test algorithm, not a production authentication primitive. It exists only
 * to prove that a first-party extension can be explicitly registered, receive bounded bytes and
 * send a result through host-owned protocol I/O.
 */
class ReferenceChallengeExtension : ProtocolExtension {
    override val id: String = ID
    override val apiVersion: Int = ProtocolExtension.CURRENT_API_VERSION
    override val entries: Set<String> = setOf(ENTRY)

    override fun create(runtime: ExtensionRuntime): ProtocolExtensionInstance =
        ReferenceChallengeInstance()

    companion object {
        const val ID: String = "lab.authenticated.xor-index"
        const val ENTRY: String = "default"
        const val RESPONSE_ENDPOINT: String = "auth.response"

        internal val KEY: ByteArray = byteArrayOf(0x5A, 0xC3.toByte(), 0x11, 0x7E)

        /** response[i] = ((challenge[i] xor key[i mod key.length]) + i) mod 256. */
        fun response(challenge: ByteArray): ByteArray = ByteArray(challenge.size) { index ->
            (((challenge[index].toInt() and 0xff) xor (KEY[index % KEY.size].toInt() and 0xff)) + index)
                .and(0xff)
                .toByte()
        }
    }
}

private class ReferenceChallengeInstance : ProtocolExtensionInstance {
    override suspend fun execute(
        entry: String,
        input: ByteArray,
        io: ControlledProtocolIo,
    ): ExtensionResult {
        require(entry == ReferenceChallengeExtension.ENTRY) { "Unknown reference entry '$entry'" }
        require(input.isNotEmpty()) { "Authentication challenge must not be empty" }
        val response = ReferenceChallengeExtension.response(input)
        io.send(ReferenceChallengeExtension.RESPONSE_ENDPOINT, response)
        return ExtensionResult.Success(response)
    }
}

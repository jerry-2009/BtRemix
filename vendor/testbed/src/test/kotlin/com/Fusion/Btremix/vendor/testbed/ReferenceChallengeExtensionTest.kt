package com.Fusion.Btremix.vendor.testbed

import com.Fusion.Btremix.protocol.extension.ControlledProtocolIo
import com.Fusion.Btremix.protocol.extension.ExtensionFrame
import com.Fusion.Btremix.protocol.extension.ExtensionFrameMatcher
import com.Fusion.Btremix.protocol.extension.ExtensionRequest
import com.Fusion.Btremix.protocol.extension.ExtensionResponse
import com.Fusion.Btremix.protocol.extension.ExtensionResult
import com.Fusion.Btremix.protocol.extension.ExtensionRuntime
import java.util.Collections
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceChallengeExtensionTest {
    @Test
    fun fixedChallenge_matchesThePublishedGoldenResponse() {
        assertArrayEquals(
            hex("4A E4 23 41 0E A8 67 05"),
            ReferenceChallengeExtension.response(hex("10 20 30 40 50 60 70 80")),
        )
    }

    @Test
    fun execute_usesOnlyDeclaredControlledIo() = runBlocking {
        val sends = Collections.synchronizedList(mutableListOf<Pair<String, ByteArray>>())
        val io = RecordingIo { endpoint, payload ->
            sends += endpoint to payload.clone()
        }
        val instance = ReferenceChallengeExtension().create(ExtensionRuntime())

        val result = instance.execute(
            ReferenceChallengeExtension.ENTRY,
            hex("10 20 30 40 50 60 70 80"),
            io,
        )

        assertEquals(ExtensionResult.Success(hex("4A E4 23 41 0E A8 67 05")), result)
        assertEquals(1, sends.size)
        assertEquals(ReferenceChallengeExtension.RESPONSE_ENDPOINT, sends.single().first)
        assertArrayEquals(hex("4A E4 23 41 0E A8 67 05"), sends.single().second)
    }

    private class RecordingIo(
        private val onSend: (String, ByteArray) -> Unit,
    ) : ControlledProtocolIo {
        override val endpoints = setOf(ReferenceChallengeExtension.RESPONSE_ENDPOINT)
        override suspend fun send(bytes: ByteArray) = error("raw send is not expected")
        override suspend fun send(endpoint: String, payload: ByteArray?) {
            onSend(endpoint, requireNotNull(payload))
        }

        override suspend fun request(request: ExtensionRequest): ExtensionResponse =
            error("request is not expected")

        override suspend fun waitFor(matcher: ExtensionFrameMatcher, timeout: Duration): ExtensionFrame =
            error("waitFor is not expected")
    }

    private fun hex(value: String): ByteArray =
        value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

package com.Fusion.Btremix.scripting

import com.Fusion.Btremix.definition.json.JsonParser
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.protocol.api.Packet
import com.Fusion.Btremix.protocol.api.PacketMatcherSpec
import com.Fusion.Btremix.protocol.api.SessionIo
import com.Fusion.Btremix.protocol.api.SessionRequest
import com.Fusion.Btremix.protocol.api.SessionSubscription
import com.Fusion.Btremix.protocol.api.TransactionResult
import com.Fusion.Btremix.scripting.api.DefaultScriptDriver
import com.Fusion.Btremix.scripting.api.ScriptCodec
import com.Fusion.Btremix.scripting.api.ScriptHost
import com.Fusion.Btremix.scripting.api.ScriptLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionScriptRuntimeTest {
    @Test
    fun sessionOpcodesRouteThroughControlledIoAndFrameHandlerCanBeCancelled() = runBlocking {
        val host = Host()
        val program = ScriptCodec.parse(
            JsonParser.parse(
                """
                [
                  {"op":"session.request","request":"query","timeoutMs":50,"into":"response"},
                  {"op":"session.waitFor","matcher":{"command":144},"timeoutMs":50},
                  {"op":"session.onFrame","matcher":{"command":128},"program":[
                    {"op":"session.send","bytes":"01 02"},
                    {"op":"state.set","key":"ready","value":true}
                  ]},
                  {"op":"state.set","key":"ready","value":true}
                ]
                """.trimIndent(),
            ),
            "actions.test.script",
        )

        val result = DefaultScriptDriver(host).execute(program, DeviceAction("test"))

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf("query"), host.io.requests.map { it.endpoint })
        assertEquals(Packet(0x90, payload = byteArrayOf(2)), host.io.waitPackets.single())
        host.io.emit(Packet(0x80))
        assertTrue(host.io.sent.single().contentEquals(byteArrayOf(1, 2)))
        assertEquals(StateValue.BooleanValue(true), host.states["ready"])

        host.io.cancelSubscriptions()
        host.io.emit(Packet(0x80))
        assertEquals(1, host.io.sent.size)
    }

    @Test
    fun byteAndStepLimitsFailBeforeUnboundedWork() = runBlocking {
        val host = Host()
        val sendTooMuch = ScriptCodec.parse(
            JsonParser.parse("""[{"op":"session.send","bytes":"010203"}]"""),
            "actions.test.script",
        )
        val byteResult = DefaultScriptDriver(host, ScriptLimits(maxBytes = 2)).execute(
            sendTooMuch,
            DeviceAction("send"),
        )
        assertTrue(byteResult is ActionResult.Failure)

        val tooManySteps = ScriptCodec.parse(
            JsonParser.parse(
                """[{"op":"repeat","count":3,"steps":[{"op":"state.set","key":"ready","value":true}]}]""",
            ),
            "actions.test.script",
        )
        val stepResult = DefaultScriptDriver(host, ScriptLimits(maxSteps = 2)).execute(
            tooManySteps,
            DeviceAction("steps"),
        )
        assertTrue(stepResult is ActionResult.Failure)
        assertFalse(host.io.sent.isNotEmpty())
    }

    private class Host : ScriptHost {
        val io = FakeSessionIo()
        val states = mutableMapOf<String, StateValue>()

        override suspend fun read(service: String, characteristic: String) = ByteArray(0)
        override suspend fun write(
            service: String,
            characteristic: String,
            value: ByteArray,
            withResponse: Boolean,
        ) = Unit
        override fun notifications(service: String, characteristic: String): Flow<ByteArray> = emptyFlow()
        override suspend fun getState(key: String): StateValue? = states[key]
        override suspend fun setState(key: String, value: StateValue) {
            states[key] = value
        }
        override suspend fun emit(name: String, value: StateValue) = Unit
        override val sessionIo: SessionIo get() = io
    }

    private class FakeSessionIo : SessionIo {
        override val endpoints = setOf("query")
        val sent = mutableListOf<ByteArray>()
        val requests = mutableListOf<SessionRequest>()
        val waitPackets = mutableListOf<Packet>()
        private val handlers = mutableListOf<Pair<PacketMatcherSpec, suspend (Packet) -> Unit>>()
        private val subscriptions = mutableListOf<SessionSubscription>()

        override suspend fun send(bytes: ByteArray) {
            sent += bytes.clone()
        }

        override suspend fun send(request: SessionRequest) = Unit

        override suspend fun request(request: SessionRequest): TransactionResult {
            requests += request
            return TransactionResult.Success(Packet(0x90, payload = byteArrayOf(2)), 1)
        }

        override suspend fun waitFor(matcher: PacketMatcherSpec, timeout: kotlin.time.Duration): Packet {
            val packet = if (matcher.command == 0x90) {
                Packet(0x90, payload = byteArrayOf(2))
            } else {
                throw IllegalArgumentException("timeout")
            }
            waitPackets += packet
            return packet
        }

        override fun onFrame(
            matcher: PacketMatcherSpec,
            handler: suspend (Packet) -> Unit,
        ): SessionSubscription {
            handlers += matcher to handler
            var active = true
            val subscription = SessionSubscription {
                if (active) handlers.removeAll { it.second === handler }
                active = false
            }
            subscriptions += subscription
            return subscription
        }

        suspend fun emit(packet: Packet) {
            handlers.toList().forEach { (matcher, handler) ->
                if (matcher.matches(packet, requestSequence = null)) handler(packet)
            }
        }

        fun cancelSubscriptions() {
            subscriptions.toList().forEach(SessionSubscription::cancel)
        }

        override suspend fun close() = cancelSubscriptions()
    }
}

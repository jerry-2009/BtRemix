package com.fusion.melodyLinkNeo.melody.hook.injection

import com.fusion.melodyLinkNeo.definition.api.MelodyAncMode
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import com.fusion.melodyLinkNeo.melody.api.MelodyAncPolicy
import com.fusion.melodyLinkNeo.melody.api.MelodyBridgeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5.2 acceptance for the `setgate` dispatcher (`HANDOFF_MELODY_M5_PLAN.md` §4 M5.2): a mapped
 * broadcast is taken over without proceeding (so the host never maps/validates/writes again), and every
 * skip path falls back to `chain.proceed()` untouched.
 */
class MelodySetgateRedirectInjectionTest {

    @Test
    fun aMappedModeType_executesOnceAndDoesNotProceed() {
        val host = FakeHost(managed = setOf(MAC), anc = policy())
        val logs = mutableListOf<String>()

        val returned = dispatch(host, modeType = 5, logs = logs)

        assertNull("onReceive is void: a take-over returns null without proceeding", returned)
        assertFalse(host.proceeded)
        assertEquals(listOf("anc.setMode"), host.executions.map { it.first })
        assertEquals(mapOf("mode" to StateValue.StringValue("anc")), host.executions.single().second)
        assertTrue(logs.any { it.contains("melody.redirect.anc") && it.contains("source=setgate") })
        assertTrue(logs.any { it.contains("melody.redirect.anc") && it.contains("action=anc.setMode") })
    }

    @Test
    fun anUnmanagedMac_proceedsAndNeverExecutes() {
        val host = FakeHost(managed = emptySet(), anc = policy())
        val logs = mutableListOf<String>()

        val returned = dispatch(host, modeType = 5, logs = logs)

        assertTrue(host.proceeded)
        assertTrue(host.executions.isEmpty())
        assertEquals(PROCEEDED, returned)
        assertTrue(logs.any { it.contains("melody.redirect.skip") && it.contains("reason=not_managed") })
    }

    @Test
    fun aChildOrUnknownModeType_proceeds() {
        val child = FakeHost(managed = setOf(MAC), anc = policy())
        dispatch(child, modeType = 3)
        assertTrue(child.proceeded)
        assertTrue(child.executions.isEmpty())

        val unknown = FakeHost(managed = setOf(MAC), anc = policy())
        dispatch(unknown, modeType = 99)
        assertTrue(unknown.proceeded)
        assertTrue(unknown.executions.isEmpty())
    }

    @Test
    fun aNoopModeType_proceedsWithoutExecuting() {
        val host = FakeHost(managed = setOf(MAC), anc = policy(), current = 0)

        dispatch(host, modeType = 5)

        assertTrue(host.proceeded)
        assertTrue(host.executions.isEmpty())
    }

    @Test
    fun aMalformedPayload_proceedsSilently() {
        val host = FakeHost(managed = setOf(MAC), anc = policy())

        val returned = dispatch(host, extraJson = """{"type":5}""")

        assertTrue(host.proceeded)
        assertTrue(host.executions.isEmpty())
        assertEquals(PROCEEDED, returned)
    }

    @Test
    fun aFailedExecute_doesNotProceedAndLogsTheFailure() {
        val host = FakeHost(managed = setOf(MAC), anc = policy())
        host.resultCode = MelodyBridgeResult.ERROR_ACTION_FAILED
        val logs = mutableListOf<String>()

        dispatch(host, modeType = 5, logs = logs)

        assertFalse(host.proceeded)
        assertTrue(
            logs.any {
                it.contains("melody.redirect.failed") && it.contains("code=${MelodyBridgeResult.ERROR_ACTION_FAILED}")
            },
        )
    }

    // --- drivers ----------------------------------------------------------------------------------

    private fun dispatch(
        host: FakeHost,
        modeType: Int = 5,
        extraJson: String = """{"type":$modeType,"mac":"$MAC"}""",
        logs: MutableList<String> = mutableListOf(),
    ): Any? {
        val dispatcher = MelodySetgateRedirectDispatcher(
            host = host,
            log = MelodyGroupLog { name, fields ->
                logs += name + fields.joinToString(" ", prefix = " ") { "${it.first}=${it.second}" }
            },
            source = "setgate",
        )
        return dispatcher.handle(extraJson, null) {
            host.proceeded = true
            PROCEEDED
        }
    }

    private fun policy(): MelodyAncPolicy = MelodyAncPolicy(
        uiVersion = 1,
        modes = listOf(
            MelodyAncMode(modeType = 5, protocolIndex = 0, state = "anc"),
            MelodyAncMode(modeType = 1, protocolIndex = 1, state = "off"),
        ),
        modeAction = "anc.setMode",
        modeParam = "mode",
    )

    private class FakeHost(
        private val managed: Set<String>,
        private val anc: MelodyAncPolicy?,
        private val current: Int? = null,
    ) : MelodySetgateRedirectHost {
        var proceeded = false
        var resultCode: Int = MelodyBridgeResult.OK
        val executions = mutableListOf<Pair<String, Map<String, StateValue>>>()

        override fun managedMacs(): Set<String> = managed

        override fun ancPolicy(mac: String): MelodyAncPolicy? = anc

        override fun currentAncIndex(mac: String): Int? = current

        override fun execute(
            mac: String,
            actionId: String,
            args: Map<String, StateValue>,
            onResult: (Int) -> Unit,
        ) {
            executions += actionId to args
            onResult(resultCode)
        }
    }

    private companion object {
        const val MAC = "14:3F:A6:02:5F:B0"
        const val PROCEEDED = "proceeded"
    }
}

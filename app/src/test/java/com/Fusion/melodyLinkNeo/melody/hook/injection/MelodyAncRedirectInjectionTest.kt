package com.fusion.melodyLinkNeo.melody.hook.injection

import com.fusion.melodyLinkNeo.definition.api.MelodyAncMode
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import com.fusion.melodyLinkNeo.melody.api.MelodyAncPolicy
import com.fusion.melodyLinkNeo.melody.api.MelodyBridgeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5.1 acceptance for the redirect dispatcher (`HANDOFF_MELODY_M5_PLAN.md` §4 M5.1): a mapped call must
 * return the host's expected value *without* proceeding, a skipped/failed call must always proceed, and
 * no failure path may throw into the host.
 */
class MelodyAncRedirectInjectionTest {

    @Test
    fun aMappedParentIndex_executesOnceAndReturnsTheHostValueWithoutProceeding() {
        val host = FakeHost(managed = setOf(MAC), anc = policy(), returned = RETURN_VALUE)
        val logs = mutableListOf<String>()

        val returned = dispatch(host, index = 0, logs = logs)

        assertEquals(RETURN_VALUE, returned)
        assertFalse(host.proceeded)
        assertEquals(listOf("anc.setMode"), host.executions.map { it.first })
        assertEquals(mapOf("mode" to StateValue.StringValue("anc")), host.executions.single().second)
        assertTrue(logs.any { it.contains("melody.redirect.anc") && it.contains("action=anc.setMode") })
    }

    @Test
    fun aMappedChildIndex_executesTheStrengthAction() {
        val host = FakeHost(managed = setOf(MAC), anc = policy(), returned = RETURN_VALUE)

        dispatch(host, index = 11)

        assertEquals(listOf("anc.setLevel"), host.executions.map { it.first })
        assertEquals(mapOf("value" to StateValue.IntValue(10)), host.executions.single().second)
    }

    @Test
    fun anUnmanagedMac_proceedsAndNeverExecutes() {
        val host = FakeHost(managed = emptySet(), anc = policy(), returned = RETURN_VALUE)
        val logs = mutableListOf<String>()

        val returned = dispatch(host, index = 0, logs = logs)

        assertTrue(host.proceeded)
        assertTrue(host.executions.isEmpty())
        assertEquals(PROCEEDED, returned)
        assertTrue(logs.any { it.contains("melody.redirect.skip") && it.contains("reason=not_managed") })
    }

    @Test
    fun anUnmappedIndex_proceeds() {
        val host = FakeHost(managed = setOf(MAC), anc = policy(), returned = RETURN_VALUE)

        val returned = dispatch(host, index = 99)

        assertTrue(host.proceeded)
        assertTrue(host.executions.isEmpty())
        assertEquals(PROCEEDED, returned)
    }

    @Test
    fun aNoopIndex_proceedsWithoutExecuting() {
        val host = FakeHost(managed = setOf(MAC), anc = policy(), current = 0, returned = RETURN_VALUE)

        dispatch(host, index = 0)

        assertTrue(host.proceeded)
        assertTrue(host.executions.isEmpty())
    }

    @Test
    fun anUnbuildableReturnValue_proceedsInsteadOfDoubleControlling() {
        val host = FakeHost(managed = setOf(MAC), anc = policy(), returned = null)
        val logs = mutableListOf<String>()

        val returned = dispatch(host, index = 0, logs = logs)

        assertTrue(host.proceeded)
        assertTrue(host.executions.isEmpty())
        assertEquals(PROCEEDED, returned)
        assertTrue(logs.any { it.contains("melody.redirect.return_fallback") })
    }

    @Test
    fun aFailedExecute_stillReturnsTheHostValueAndLogsTheFailure() {
        val host = FakeHost(managed = setOf(MAC), anc = policy(), returned = RETURN_VALUE)
        host.resultCode = MelodyBridgeResult.ERROR_ACTION_FAILED
        val logs = mutableListOf<String>()

        val returned = dispatch(host, index = 0, logs = logs)

        assertEquals(RETURN_VALUE, returned)
        assertFalse(host.proceeded)
        assertTrue(logs.any { it.contains("melody.redirect.failed") && it.contains("code=${MelodyBridgeResult.ERROR_ACTION_FAILED}") })
    }

    @Test
    fun aMalformedMac_proceedsSilently() {
        val host = FakeHost(managed = setOf(MAC), anc = policy(), returned = RETURN_VALUE)
        val logs = mutableListOf<String>()

        val returned = dispatch(host, index = 0, mac = "not-a-mac", logs = logs)

        assertTrue(host.proceeded)
        assertTrue(host.executions.isEmpty())
        assertEquals(PROCEEDED, returned)
        assertTrue(logs.isEmpty())
    }

    // --- drivers ----------------------------------------------------------------------------------

    private fun dispatch(
        host: FakeHost,
        index: Int,
        mac: String = MAC,
        logs: MutableList<String> = mutableListOf(),
    ): Any? {
        val dispatcher = MelodyAncRedirectDispatcher(
            host = host,
            log = MelodyGroupLog { name, fields ->
                logs += name + fields.joinToString(" ", prefix = " ") { "${it.first}=${it.second}" }
            },
            source = "v0",
        )
        return dispatcher.handle(index, mac) {
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
        strength = STRENGTH,
        modeAction = "anc.setMode",
        modeParam = "mode",
        strengthParam = "value",
    )

    private class FakeHost(
        private val managed: Set<String>,
        private val anc: MelodyAncPolicy?,
        private val current: Int? = null,
        private val returned: Any?,
    ) : MelodyAncRedirectHost {
        var proceeded = false
        var resultCode: Int = MelodyBridgeResult.OK
        val executions = mutableListOf<Pair<String, Map<String, StateValue>>>()

        override fun managedMacs(): Set<String> = managed

        override fun ancPolicy(mac: String): MelodyAncPolicy? = anc

        override fun currentAncIndex(mac: String): Int? = current

        override fun redirect(
            rawMac: String,
            mac: String,
            actionId: String,
            args: Map<String, StateValue>,
            onResult: (Int) -> Unit,
        ): Any? {
            // Mirrors BridgeHost: an unbuildable return value must not start a write.
            if (returned == null) return null
            executions += actionId to args
            onResult(resultCode)
            return returned
        }
    }

    private companion object {
        const val MAC = "14:3F:A6:02:5F:B0"
        const val PROCEEDED = "proceeded"
        val RETURN_VALUE = Any()
        val STRENGTH = com.fusion.melodyLinkNeo.definition.api.MelodyAncStrengthDefinition(
            state = "ancLevel",
            action = "anc.setLevel",
            levels = listOf(
                com.fusion.melodyLinkNeo.definition.api.MelodyAncStrengthLevel(modeType = 3, protocolIndex = 10, level = 1),
                com.fusion.melodyLinkNeo.definition.api.MelodyAncStrengthLevel(modeType = 8, protocolIndex = 11, level = 10),
            ),
        )
    }
}

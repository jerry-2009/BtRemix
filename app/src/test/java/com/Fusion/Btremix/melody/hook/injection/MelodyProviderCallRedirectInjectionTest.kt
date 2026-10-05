package com.Fusion.Btremix.melody.hook.injection

import com.Fusion.Btremix.definition.api.MelodyAncMode
import com.Fusion.Btremix.definition.api.MelodyAncStrengthDefinition
import com.Fusion.Btremix.definition.api.MelodyAncStrengthLevel
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.melody.api.MelodyAncPolicy
import com.Fusion.Btremix.melody.api.MelodyBridgeResult
import com.Fusion.Btremix.melody.api.MelodyProviderCallPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5.3 acceptance for the provider dispatcher (`HANDOFF_MELODY_M5_PLAN.md` §4 M5.3): a mapped
 * `melody_method_noise_reduction` is taken over without proceeding (so the host never validates or
 * writes again) and returns the `null` its original caller already consumed; every skip path falls
 * back to `chain.proceed()` untouched.
 */
class MelodyProviderCallRedirectInjectionTest {

    @Test
    fun aMappedParentModeType_executesOnceAndDoesNotProceed() {
        val host = FakeHost(managed = setOf(MAC), anc = policy())
        val logs = mutableListOf<String>()

        val returned = dispatch(host, modeType = 5, logs = logs)

        assertNull("17.6.3 always returns super.call's null; a take-over returns it without proceeding", returned)
        assertFalse(host.proceeded)
        assertEquals(listOf("anc.setMode"), host.executions.map { it.first })
        assertEquals(mapOf("mode" to StateValue.StringValue("anc")), host.executions.single().second)
        assertTrue(logs.any { it.contains("melody.redirect.anc") && it.contains("source=provider") })
        assertTrue(logs.any { it.contains("melody.redirect.anc") && it.contains("action=anc.setMode") })
    }

    @Test
    fun aMappedChildModeType_routesToTheStrengthAction() {
        // The provider's host resolver (`L.n`) also searches each entry's `childrenMode`, so the
        //「降噪效果」positions are reachable through this entrance (unlike `setgate`).
        val host = FakeHost(managed = setOf(MAC), anc = policy())

        dispatch(host, modeType = 3)

        assertFalse(host.proceeded)
        assertEquals(listOf("anc.setLevel"), host.executions.map { it.first })
        assertEquals(mapOf("value" to StateValue.IntValue(1)), host.executions.single().second)
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
    fun aNonNoiseReductionMethod_proceedsWithoutConsultingTheEnvelope() {
        val host = FakeHost(managed = setOf(MAC), anc = policy())

        val returned = dispatch(host, method = "melody_method_spatial", modeType = 5)

        assertTrue(host.proceeded)
        assertTrue(host.executions.isEmpty())
        assertEquals(PROCEEDED, returned)
    }

    @Test
    fun anUnknownModeType_proceeds() {
        val host = FakeHost(managed = setOf(MAC), anc = policy())

        dispatch(host, modeType = 99)

        assertTrue(host.proceeded)
        assertTrue(host.executions.isEmpty())
    }

    @Test
    fun aNoopModeType_proceedsWithoutExecuting() {
        val host = FakeHost(managed = setOf(MAC), anc = policy(), current = 0)

        dispatch(host, modeType = 5)

        assertTrue(host.proceeded)
        assertTrue(host.executions.isEmpty())
    }

    @Test
    fun aMissingOrUnusableAddress_proceeds() {
        val host = FakeHost(managed = setOf(MAC), anc = policy())

        val returned = dispatch(host, extras = mapOf("type" to 5))

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
        method: String = MelodyProviderCallPolicy.METHOD_NOISE_REDUCTION,
        modeType: Int = 5,
        extras: Map<String, Any> = mapOf("address" to MAC, "type" to modeType),
        logs: MutableList<String> = mutableListOf(),
    ): Any? {
        val dispatcher = MelodyProviderCallRedirectDispatcher(
            host = host,
            log = MelodyGroupLog { name, fields ->
                logs += name + fields.joinToString(" ", prefix = " ") { "${it.first}=${it.second}" }
            },
            source = "provider",
        )
        return dispatcher.handle(method, mapExtras(extras)) {
            host.proceeded = true
            PROCEEDED
        }
    }

    private fun mapExtras(values: Map<String, Any>): MelodyProviderCallPolicy.Extras =
        object : MelodyProviderCallPolicy.Extras {
            override fun string(key: String): String? = values[key] as? String

            override fun int(key: String): Int? = values[key] as? Int
        }

    private fun policy(): MelodyAncPolicy = MelodyAncPolicy(
        uiVersion = 1,
        modes = listOf(
            MelodyAncMode(modeType = 5, protocolIndex = 0, state = "anc"),
            MelodyAncMode(modeType = 1, protocolIndex = 1, state = "off"),
        ),
        strength = MelodyAncStrengthDefinition(
            state = "ancLevel",
            action = "anc.setLevel",
            levels = listOf(
                MelodyAncStrengthLevel(modeType = 3, protocolIndex = 10, level = 1),
                MelodyAncStrengthLevel(modeType = 8, protocolIndex = 11, level = 10),
                MelodyAncStrengthLevel(modeType = 4, protocolIndex = 12, level = 20),
            ),
        ),
        modeAction = "anc.setMode",
        modeParam = "mode",
        strengthParam = "value",
    )

    private class FakeHost(
        private val managed: Set<String>,
        private val anc: MelodyAncPolicy?,
        private val current: Int? = null,
    ) : MelodyProviderCallRedirectHost {
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

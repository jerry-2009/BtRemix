package com.fusion.melodyLinkNeo.core.hook

import com.fusion.melodyLinkNeo.core.hook.api.HookAnchorCoverage
import com.fusion.melodyLinkNeo.core.hook.api.HookGatewayState
import com.fusion.melodyLinkNeo.core.hook.api.ModuleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DEVICE_CENTER_UI_PLAN §3.3: the four module states and their thresholds. */
class ModuleStatusPolicyTest {

    private val policy = ModuleStatusPolicy()

    @Test
    fun missingHost_isStoppedAndGuidesTheUser() {
        val ui = policy.evaluate(HookGatewayState(hostInstalled = false))
        assertEquals(ModuleStatus.STOPPED, ui.status)
        assertTrue(ui.title.isNotBlank())
        assertTrue(ui.description.contains("Melody"))
    }

    @Test
    fun awaitingHost_isWarning() {
        val ui = policy.evaluate(HookGatewayState(hostInstalled = true, awaitingHost = true, version = "17.6.3"))
        assertEquals(ModuleStatus.WARNING, ui.status)
        assertEquals("17.6.3", ui.version)
    }

    @Test
    fun noAnchorReportYet_isWarningNotError() {
        val ui = policy.evaluate(HookGatewayState(hostInstalled = true, coverage = HookAnchorCoverage(0, 0)))
        assertEquals(ModuleStatus.WARNING, ui.status)
    }

    @Test
    fun allAnchorsMissing_isError() {
        val ui = policy.evaluate(
            HookGatewayState(
                hostInstalled = true,
                coverage = HookAnchorCoverage(0, 12),
                missingAnchorIds = listOf("anchor.a"),
            ),
        )
        assertEquals(ModuleStatus.ERROR, ui.status)
        assertEquals("0/12", ui.coverage)
    }

    @Test
    fun partialCoverage_isWarning() {
        val ui = policy.evaluate(HookGatewayState(hostInstalled = true, coverage = HookAnchorCoverage(9, 12)))
        assertEquals(ModuleStatus.WARNING, ui.status)
        assertTrue(ui.description.contains("9/12"))
    }

    @Test
    fun coverageAtThreshold_isRunning() {
        val ui = policy.evaluate(HookGatewayState(hostInstalled = true, coverage = HookAnchorCoverage(11, 12)))
        assertEquals(ModuleStatus.RUNNING, ui.status)
    }

    @Test
    fun coverageExactlyAtBoundary_isRunning() {
        // 9/10 == 0.9 == minCoverage, and the rule degrades only strictly below it.
        val ui = policy.evaluate(HookGatewayState(hostInstalled = true, coverage = HookAnchorCoverage(9, 10)))
        assertEquals(ModuleStatus.RUNNING, ui.status)
    }
}

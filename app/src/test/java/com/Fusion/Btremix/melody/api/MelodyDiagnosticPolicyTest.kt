package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5.4 D-28/D-29: which events cross the binder and which survive the diagnostics switch. */
class MelodyDiagnosticPolicyTest {

    @Test
    fun forwardsTheRedirectAnchorAndGateLines() {
        assertTrue(MelodyDiagnosticPolicy.forwardsToBridge("melody.redirect.anc"))
        assertTrue(MelodyDiagnosticPolicy.forwardsToBridge("melody.anchor.hit"))
        assertTrue(MelodyDiagnosticPolicy.forwardsToBridge("melody.anchor.missing"))
        assertTrue(MelodyDiagnosticPolicy.forwardsToBridge("melody.host.version_unsupported"))
    }

    @Test
    fun keepsBridgeAndObservationTrafficOnLogcat() {
        assertFalse(MelodyDiagnosticPolicy.forwardsToBridge("melody.bridge.execute"))
        assertFalse(MelodyDiagnosticPolicy.forwardsToBridge("melody.command.receive"))
        assertFalse(MelodyDiagnosticPolicy.forwardsToBridge("melody.panel.row"))
    }

    @Test
    fun switchOnlyKeepsTheLoadTimeLines() {
        // Turning diagnostics off must actually reduce work: normal event lines are suppressed...
        assertTrue(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.redirect.anc"))
        assertTrue(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.anchor.hit"))
        // ...while "is the module installed in this process?" stays answerable.
        assertFalse(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.injection.install"))
        assertFalse(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.scope.loaded"))
    }
}

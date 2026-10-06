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
        // ...and the whitelist / card injections stay observable: their answer lines are the only way
        // to tell "the hook ran" apart from "the hook was never installed" (2026-10-06 card debug).
        assertFalse(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.inject.whitelist_repo"))
        assertFalse(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.inject.whitelist_lookup"))
        // ...and the desktop-card chain itself: "row re-published?", "which step fail-opened?" and "did
        // the host receive a push at all?" must be answerable with the switch in whatever state the
        // framework cached.
        assertFalse(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.anc.card.show"))
        assertFalse(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.anc.card.skip"))
        assertFalse(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.anc.card.publish"))
        assertFalse(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.anc.refresh.armed"))
        assertFalse(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.anc.refresh.pass"))
        // The noisy detail-page / panel evidence stays behind the switch (2026-10-06 noise trim).
        assertTrue(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.anc.refreshed"))
        assertTrue(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.anc.noiseinfo"))
        assertTrue(MelodyDiagnosticPolicy.suppressedWhenDisabled("melody.devicecard.call4"))
    }
}

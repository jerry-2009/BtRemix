package com.Fusion.Btremix.melody.api

/**
 * Which structured events travel from the host process back to BtRemix (M5.4 D-28/D-29).
 *
 * Only the redirect/anchoring/versioning lines are forwarded: they are the evidence base for the
 * M5.5 matrix and they are low-volume (one line per control action, a handful per install). The
 * `melody.bridge.*` traffic stays on logcat - forwarding it would put every binder exchange on the
 * binder again.
 */
object MelodyDiagnosticPolicy {

    /** Event-name prefixes the host forwards to [com.Fusion.Btremix.melody.bridge.IMelodyBridge]. */
    val FORWARD_PREFIXES: List<String> = listOf(
        "melody.redirect.",
        "melody.anchor.",
        "melody.host.",
        "melody.diag.",
    )

    /**
     * Prefixes that are still emitted when `melody_bridge.diagnostics_enabled` is off: the load-time
     * lines that answer "is the module even installed in this process?" (D-29). Everything else is
     * suppressed, which is what makes the switch actually reduce work.
     *
     * The desktop-card chain is always on as well (2026-10-06): `melody.inject.` (the whitelist answers),
     * `melody.anc.card.` (the row re-publish and its fail-open reasons) and the two
     * `melody.anc.refresh.armed|pass` lines that say whether the host received a push at all.
     *
     * The scope is deliberately narrow. The LSPosed remote-preference cache can keep the switch reading
     * `false` in a freshly started host even after the user enabled it, so without these the card looked
     * "not hooked" while it had actually answered and bailed out; but the card is not the only user of
     * `melody.anc.` - `melody.anc.refreshed`, `melody.anc.noiseinfo.*` and
     * `melody.devicecard.*` (the provider protocol dump, bundles and cursors included) are the noisy,
     * detail-page/panel-side evidence and stay behind the switch.
     */
    val ALWAYS_PREFIXES: List<String> = listOf(
        "melody.scope.",
        "melody.injection.",
        "melody.inject.",
        "melody.anc.card.",
        "melody.anc.refresh.armed",
        "melody.anc.refresh.pass",
        "melody.observation.disabled",
        "melody.bridge.disabled",
    )

    /** Ring-buffer size on the BtRemix side; the export says so when it drops older entries. */
    const val MAX_EVENTS: Int = 500

    /** Cap per event, so one runaway field list cannot blow the binder transaction. */
    const val MAX_FIELDS: Int = 24

    fun forwardsToBridge(name: String): Boolean = FORWARD_PREFIXES.any(name::startsWith)

    fun suppressedWhenDisabled(name: String): Boolean = ALWAYS_PREFIXES.none(name::startsWith)
}

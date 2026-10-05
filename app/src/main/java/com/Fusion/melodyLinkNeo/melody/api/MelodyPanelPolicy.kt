package com.fusion.melodyLinkNeo.melody.api

/**
 * The panel policy the Melody detail page applies for one managed MAC (HANDOFF_MELODY_M4_PLAN.md §3
 * M4.0, MELODY_BRIDGE_SPEC §4.2 / D5).
 *
 * It is the envelope-side reading of `melody.panel` (the Definition-side model is
 * [com.fusion.melodyLinkNeo.definition.api.MelodyPanelDefinition]) and is produced by
 * [MelodyProviderMerge.panelOf]. It stays Android-free so the whole degradation contract is unit
 * tested on the JVM instead of only observable on a phone.
 *
 * Semantics frozen in M4.0:
 * - [sectionTitle] is the title of the **custom** group BtRemix inserts - it never names an official
 *   row and is not part of hide/grey.
 * - [hideSections] / [hideKeys] / [greyKeys] only ever name **official** panel keys; a key in the
 *   BtRemix namespace ([com.fusion.melodyLinkNeo.definition.api.MelodyPanelDefinition.CUSTOM_KEY_PREFIX])
 *   is rejected on both the Definition and the envelope side.
 * - [missingReason] is the degradation signal. When it is non-null the whole policy is inert: the
 *   caller must not hide, grey or insert anything for this MAC, and logs
 *   `melody.panel.policy_missing reason=<missingReason>` (fail-open, spec §4 item 5).
 */
data class MelodyPanelPolicy(
    val sectionTitle: String,
    val hideSections: Set<String> = emptySet(),
    val hideKeys: Set<String> = emptySet(),
    val greyKeys: Set<String> = emptySet(),
    /**
     * M4.3c (D-8): the self-built「高级功能」group the panel adds after the official `sound` group,
     * already routed and resolved by the projection builder. `null` means "insert nothing"; a
     * present-but-malformed `group` node degrades to `null` while the hide/grey policy survives.
     */
    val group: MelodyPanelGroup? = null,
    val missingReason: String? = null,
) {
    /** True when the envelope carried a usable `panel` node; false for the inert/degraded policy. */
    val present: Boolean get() = missingReason == null

    companion object {
        /** Degradation reasons, kept as constants so logs, hooks and tests share one spelling. */
        const val MISSING_ENVELOPE: String = "envelope"
        const val MISSING_NODE: String = "panel"
        const val FIELD_HIDE_SECTIONS: String = "hideSections"
        const val FIELD_HIDE_KEYS: String = "hideKeys"
        const val FIELD_GREY_KEYS: String = "greyKeys"

        /** The inert policy used whenever the envelope or the `panel` node is missing or malformed. */
        fun missing(sectionTitle: String, reason: String): MelodyPanelPolicy =
            MelodyPanelPolicy(sectionTitle = sectionTitle, missingReason = reason)
    }
}

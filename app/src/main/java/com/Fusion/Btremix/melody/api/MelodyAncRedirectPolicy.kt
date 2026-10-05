package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.device.runtime.StateValue

/**
 * The pure half of the M5.1 ANC redirect (`HANDOFF_MELODY_M5_PLAN.md` §4 M5.1, decision D-18).
 *
 * The host writes an ANC change as `EarphoneRepositoryClientImpl.v0(protocolIndex, mac)`; this policy
 * answers "should that call be turned into a BtRemix action, and into which one?". Nothing here knows
 * about Android, Xposed or the `MelodyBridgeClient`: the lookups arrive as plain values, which is what
 * lets the whole mapping table be pinned on the JVM.
 *
 * The single source of truth is the projection envelope's `melody.anc` node (already parsed into
 * [MelodyAncPolicy]): the parent `modes[]` carry `protocolIndex -> state` (written with the Definition's
 * own mode action), and `strength.levels[]` carry `protocolIndex -> level` (written with
 * `strength.action`). Anything not in either table is *not* guessed - it degrades to
 * [Decision.Skip] and the caller lets the host run its own path.
 */
object MelodyAncRedirectPolicy {

    /** No live/persisted envelope for this MAC; the host path stays untouched. */
    const val REASON_NOT_MANAGED: String = "not_managed"

    /** Managed, but no projection envelope is available yet (cache miss). */
    const val REASON_NO_ENVELOPE: String = "no_envelope"

    /** The envelope carries no usable `melody.anc` node. */
    const val REASON_NO_ANC: String = "no_anc"

    /** The requested index is already the projected one; writing would be a redundant control. */
    const val REASON_NOOP: String = "noop"

    /** The index is in neither the parent nor the strength table (or the action id/arg is missing). */
    const val REASON_UNMAPPED: String = "unmapped"

    sealed interface Decision {
        /** One BtRemix action with its argument map, exactly as `IMelodyBridge.execute` expects it. */
        data class Redirect(val actionId: String, val args: Map<String, StateValue>) : Decision

        /** Do not take over; the caller must run the host's own path (`chain.proceed()`). */
        data class Skip(val reason: String) : Decision
    }

    /**
     * Decides how the host's `v0(protocolIndex, mac)` call is handled.
     *
     * @param mac normalised MAC (unused by the mapping itself; kept so the decision is self-describing
     *   and callers/tests read like the log line).
     * @param managed whether [mac] is in the managed set (`managedMacsFast`).
     * @param anc the envelope's `melody.anc` policy, or `null` when there is no envelope at all.
     * @param currentIndex the `protocolIndex` the Definition currently projects for this device, or
     *   `null` when it is unknown; equal to [protocolIndex] means "already there" ([REASON_NOOP]).
     */
    @Suppress("UNUSED_PARAMETER") // `mac` documents the scope of the decision; the caller logs it.
    fun decide(
        mac: String,
        protocolIndex: Int,
        managed: Boolean,
        anc: MelodyAncPolicy?,
        currentIndex: Int?,
    ): Decision {
        if (!managed) return Decision.Skip(REASON_NOT_MANAGED)
        if (anc == null) return Decision.Skip(REASON_NO_ENVELOPE)
        if (anc.isEmpty && anc.strength == null) return Decision.Skip(REASON_NO_ANC)
        if (currentIndex != null && currentIndex == protocolIndex) return Decision.Skip(REASON_NOOP)

        // Parent table: modeType-indexed host cells select a Definition mode state. The action id and
        // parameter name are derived from the Definition (M5.1), so a package without them fails open.
        anc.modes.firstOrNull { it.protocolIndex == protocolIndex }?.let { mode ->
            val action = anc.modeAction?.takeIf { it.isNotBlank() } ?: return Decision.Skip(REASON_UNMAPPED)
            val param = anc.modeParam?.takeIf { it.isNotBlank() } ?: return Decision.Skip(REASON_UNMAPPED)
            return Decision.Redirect(action, mapOf(param to StateValue.StringValue(mode.state)))
        }

        // Child table: the「降噪效果」positions write the Definition's own strength state.
        anc.strength?.levels?.firstOrNull { it.protocolIndex == protocolIndex }?.let { level ->
            val action = anc.strength.action.takeIf { it.isNotBlank() } ?: return Decision.Skip(REASON_UNMAPPED)
            val param = anc.strengthParam?.takeIf { it.isNotBlank() } ?: return Decision.Skip(REASON_UNMAPPED)
            return Decision.Redirect(action, mapOf(param to StateValue.IntValue(level.level)))
        }

        return Decision.Skip(REASON_UNMAPPED)
    }

    /**
     * M5.2: the same mapping, keyed on the host's `modeType` instead of a `protocolIndex`.
     *
     * The `setgate` broadcast and the device-centre SDK's `melody_method_noise_reduction` carry only a
     * `modeType`; the host itself resolves that through its injected `noiseReductionMode` table, which
     * is the parent [MelodyAncPolicy.modes] here (`docs/melody-capability-map.md` §8.5/§8.6). The
     * child「降噪效果」positions are nested under the noise-cancelling entry and are never reached this
     * way, so a `modeType` found only there (or nowhere) is not guessed: it stays [REASON_UNMAPPED] and
     * the caller lets the host run its own path.
     */
    @Suppress("UNUSED_PARAMETER") // `mac` documents the scope of the decision; the caller logs it.
    fun decideByModeType(
        mac: String,
        modeType: Int,
        managed: Boolean,
        anc: MelodyAncPolicy?,
        currentIndex: Int?,
    ): Decision {
        if (!managed) return Decision.Skip(REASON_NOT_MANAGED)
        if (anc == null) return Decision.Skip(REASON_NO_ENVELOPE)
        if (anc.isEmpty && anc.strength == null) return Decision.Skip(REASON_NO_ANC)
        val index = anc.modes.firstOrNull { it.modeType == modeType }?.protocolIndex
            ?: return Decision.Skip(REASON_UNMAPPED)
        return decide(mac, index, managed, anc, currentIndex)
    }
}

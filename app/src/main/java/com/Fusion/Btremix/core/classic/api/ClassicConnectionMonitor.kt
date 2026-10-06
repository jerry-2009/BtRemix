package com.Fusion.Btremix.core.classic.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * One classic-Bluetooth link transition observed on the phone (HANDOFF_AUTO_SESSION.md §6 step 1).
 *
 * [address] is the raw address the platform reports. Normalising it (trim + upper case) stays in the
 * `device` layer because `core` must not know about `SessionRegistry`.
 */
data class ClassicLinkEvent(
    val address: String,
    val name: String?,
    val connected: Boolean,
)

/**
 * Neutral source of "a classic link came up / went down" plus "which classic audio devices are
 * connected right now".
 *
 * Both signals answer different halves of the same question (HANDOFF_AUTO_SESSION.md §2): the link
 * edges drive open/close, while the A2DP/HFP profile list is the current set used to make up for
 * transitions that happened before the monitor started.
 */
interface ClassicConnectionMonitor {
    /**
     * Link transitions.
     *
     * ACL connect/disconnect are the primary source, and the A2DP/HFP profile connection-state
     * broadcasts are folded in as the documented fallback (HANDOFF_AUTO_SESSION.md §2/§8): some
     * ROMs do not deliver `ACTION_ACL_*` to app receivers, but still deliver the profile ones.
     */
    val events: Flow<ClassicLinkEvent>

    /**
     * Emits when the local adapter is switched off.
     *
     * At that point every classic link is gone at once, so the owner must drop all of its holds even
     * though the per-device disconnect broadcasts may never arrive.
     */
    val adapterOff: Flow<Unit>

    /** Classic audio devices currently connected (A2DP/HEADSET profile proxies). */
    suspend fun connectedDevices(): List<ClassicDevice>

    /** Registers the platform receiver. Must be called once by the owning component. */
    fun start(scope: CoroutineScope)

    fun stop()
}

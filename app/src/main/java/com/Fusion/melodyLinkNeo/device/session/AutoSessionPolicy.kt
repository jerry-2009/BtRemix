package com.fusion.melodyLinkNeo.device.session

import com.fusion.melodyLinkNeo.core.classic.api.ClassicLinkEvent
import com.fusion.melodyLinkNeo.device.registry.DeviceDiscoveryKind
import com.fusion.melodyLinkNeo.device.registry.DeviceEntry

/** Outcome of evaluating one link event against the auto-session rules. */
sealed interface AutoSessionDecision {
    val mac: String

    /** Rising edge that should open (or re-open) a session for [mac]. */
    data class Open(override val mac: String, val packageId: String, val name: String?) : AutoSessionDecision

    /** Falling edge for a device this feature currently holds. */
    data class Release(override val mac: String, val reason: String) : AutoSessionDecision

    /** Nothing to do, with a machine-readable [reason] for the structured log. */
    data class Skip(override val mac: String, val reason: String) : AutoSessionDecision
}

/**
 * Pure rules for "蓝牙连接时自动建立会话" (HANDOFF_AUTO_SESSION.md §6 step 3).
 *
 * A session may only be opened for a **rising** classic-link edge that matches an *enabled* device
 * package, i.e. a `BONDED_CLASSIC` [DeviceEntry] from `DeviceRegistry.devices`. Skipping a MAC while
 * it is in flight or already held is what stops BtRemix's own SPP connection - which brings the ACL
 * up again - from re-triggering itself.
 */
class AutoSessionPolicy(
    /** One retry per entry after the initial attempt (HANDOFF_AUTO_SESSION.md §6 step 3). */
    private val retryDelaysMs: List<Long> = listOf(2_000L, 6_000L),
) {
    fun decide(
        enabled: Boolean,
        event: ClassicLinkEvent,
        devices: List<DeviceEntry>,
        heldMacs: Set<String>,
        inFlightMacs: Set<String>,
    ): AutoSessionDecision {
        val mac = SessionRegistry.normalize(event.address)
        if (!event.connected) {
            return if (mac in heldMacs) {
                AutoSessionDecision.Release(mac, "link_down")
            } else {
                AutoSessionDecision.Skip(mac, "link_down_not_held")
            }
        }
        if (mac in inFlightMacs) return AutoSessionDecision.Skip(mac, "in_flight")
        if (mac in heldMacs) return AutoSessionDecision.Skip(mac, "already_held")
        if (!enabled) return AutoSessionDecision.Skip(mac, "disabled")
        val entry = devices.firstOrNull { it.key == mac } ?: return AutoSessionDecision.Skip(mac, "unmatched")
        if (entry.discovery != DeviceDiscoveryKind.BONDED_CLASSIC) {
            return AutoSessionDecision.Skip(mac, "not_classic")
        }
        return AutoSessionDecision.Open(mac, entry.packageId, entry.name)
    }

    /** MACs to release when the switch is turned off. */
    fun onDisabled(heldMacs: Set<String>): List<String> = heldMacs.sorted()

    /**
     * Delay before the next attempt after [attemptsMade] failures, or `null` when the budget is
     * exhausted and the connector must wait for the next rising edge.
     */
    fun retryDelayMs(attemptsMade: Int): Long? = retryDelaysMs.getOrNull(attemptsMade - 1)
}

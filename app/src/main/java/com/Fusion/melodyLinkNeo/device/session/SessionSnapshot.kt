package com.fusion.melodyLinkNeo.device.session

import com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState
import com.fusion.melodyLinkNeo.device.runtime.StateEntry
import com.fusion.melodyLinkNeo.device.runtime.StateValue

/**
 * A pure-data copy of one managed session's observable surface (MELODY_BRIDGE_SPEC §12 M2a).
 *
 * M2a adds this so a late-joining front-end can read the current lifecycle and state without
 * depending on the (non-replaying) event stream: the Compose UI keeps collecting flows, while the
 * Melody bridge (M2b) will hand this snapshot across the process boundary. Keeping it a plain data
 * class with no live references means it is safe to serialise and safe to cache.
 */
data class SessionSnapshot(
    val mac: String,
    val lifecycle: DeviceLifecycleState,
    val state: Map<String, StateEntry>,
)

/** Builds a detached copy of [session]'s current lifecycle and state. */
internal fun snapshotOf(mac: String, session: com.fusion.melodyLinkNeo.device.runtime.ProtocolSession): SessionSnapshot =
    SessionSnapshot(
        mac = mac,
        lifecycle = session.lifecycle.value,
        state = session.state.entries.value.mapValues { (_, entry) ->
            entry.copy(value = entry.value.detached())
        },
    )

/** Clones the mutable parts of a state value so the snapshot never aliases live session data. */
private fun StateValue.detached(): StateValue = when (this) {
    is StateValue.BytesValue -> StateValue.BytesValue(value.clone())
    is StateValue.ListValue -> StateValue.ListValue(value.map { it.detached() })
    is StateValue.MapValue -> StateValue.MapValue(value.mapValues { (_, item) -> item.detached() })
    else -> this
}

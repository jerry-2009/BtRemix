package com.fusion.melodyLinkNeo.melody.api

import com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState
import com.fusion.melodyLinkNeo.device.runtime.RuntimeError

/**
 * Flattens [DeviceLifecycleState] to a name + optional error message for the process boundary.
 *
 * The state machine holds `Error(RuntimeError)`, which is an open set of classes and cannot travel
 * through AIDL. The panel only needs to distinguish "ready / connecting / disconnected / error" and,
 * for errors, a human-readable message, so the wire form is a name plus that message. The reverse
 * direction is deliberately best-effort: a late-joining panel rebuilds the closest state rather than
 * demanding the original exception.
 */
object MelodyLifecycleWire {

    const val CREATED: String = "Created"
    const val DISCOVERED: String = "Discovered"
    const val CONNECTING: String = "Connecting"
    const val CONNECTED: String = "Connected"
    const val INITIALIZING: String = "Initializing"
    const val READY: String = "Ready"
    const val REFRESHING_STATE: String = "RefreshingState"
    const val DISCONNECTING: String = "Disconnecting"
    const val DISCONNECTED: String = "Disconnected"
    const val ERROR: String = "Error"

    fun nameOf(state: DeviceLifecycleState): String = when (state) {
        DeviceLifecycleState.Created -> CREATED
        DeviceLifecycleState.Discovered -> DISCOVERED
        DeviceLifecycleState.Connecting -> CONNECTING
        DeviceLifecycleState.Connected -> CONNECTED
        DeviceLifecycleState.Initializing -> INITIALIZING
        DeviceLifecycleState.Ready -> READY
        DeviceLifecycleState.RefreshingState -> REFRESHING_STATE
        DeviceLifecycleState.Disconnecting -> DISCONNECTING
        DeviceLifecycleState.Disconnected -> DISCONNECTED
        is DeviceLifecycleState.Error -> ERROR
    }

    fun messageOf(state: DeviceLifecycleState): String? =
        (state as? DeviceLifecycleState.Error)?.error?.message

    fun stateOf(name: String?, errorMessage: String?): DeviceLifecycleState = when (name) {
        CREATED -> DeviceLifecycleState.Created
        DISCOVERED -> DeviceLifecycleState.Discovered
        CONNECTING -> DeviceLifecycleState.Connecting
        CONNECTED -> DeviceLifecycleState.Connected
        INITIALIZING -> DeviceLifecycleState.Initializing
        READY -> DeviceLifecycleState.Ready
        REFRESHING_STATE -> DeviceLifecycleState.RefreshingState
        DISCONNECTING -> DeviceLifecycleState.Disconnecting
        ERROR -> DeviceLifecycleState.Error(
            RuntimeError.InitializationFailed(errorMessage ?: "device error"),
        )
        else -> DeviceLifecycleState.Disconnected
    }
}

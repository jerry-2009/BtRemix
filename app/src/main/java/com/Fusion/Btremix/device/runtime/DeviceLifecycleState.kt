package com.Fusion.Btremix.device.runtime

sealed interface DeviceLifecycleState {
    data object Created : DeviceLifecycleState
    data object Discovered : DeviceLifecycleState
    data object Connecting : DeviceLifecycleState
    data object Connected : DeviceLifecycleState
    data object Initializing : DeviceLifecycleState
    data object Ready : DeviceLifecycleState
    data object RefreshingState : DeviceLifecycleState
    data object Disconnecting : DeviceLifecycleState
    data object Disconnected : DeviceLifecycleState
    data class Error(val error: RuntimeError) : DeviceLifecycleState
}

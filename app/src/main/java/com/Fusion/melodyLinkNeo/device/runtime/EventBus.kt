package com.fusion.melodyLinkNeo.device.runtime

import com.fusion.melodyLinkNeo.core.bluetooth.api.BleCharacteristic
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Runtime event stream. It intentionally has no replay buffer. */
class EventBus(private val clock: Clock = Clock.systemUTC()) {
    private val eventsImpl = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<DeviceEvent> = eventsImpl.asSharedFlow()
    val flow: SharedFlow<DeviceEvent> = events

    suspend fun emit(event: DeviceEvent) {
        eventsImpl.emit(event)
    }

    suspend fun publish(event: DeviceEvent) = emit(event)

    fun now(): Instant = clock.instant()
}

sealed interface DeviceEvent {
    val deviceId: String
    val timestamp: Instant

    data class LifecycleChanged(
        override val deviceId: String,
        override val timestamp: Instant,
        val previous: DeviceLifecycleState,
        val current: DeviceLifecycleState,
    ) : DeviceEvent

    data class DeviceConnected(override val deviceId: String, override val timestamp: Instant) : DeviceEvent
    data class DeviceReady(override val deviceId: String, override val timestamp: Instant) : DeviceEvent
    data class DeviceDisconnected(override val deviceId: String, override val timestamp: Instant) : DeviceEvent

    data class StateChanged(
        override val deviceId: String,
        override val timestamp: Instant,
        val key: String,
        val oldValue: StateValue?,
        val newValue: StateValue,
        val entry: StateEntry,
    ) : DeviceEvent

    data class NotificationReceived(
        override val deviceId: String,
        override val timestamp: Instant,
        val characteristic: BleCharacteristic,
        val data: ByteArray,
    ) : DeviceEvent {
        override fun equals(other: Any?): Boolean = other is NotificationReceived &&
            deviceId == other.deviceId && timestamp == other.timestamp && characteristic == other.characteristic && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * (31 * (31 * deviceId.hashCode() + timestamp.hashCode()) + characteristic.hashCode()) + data.contentHashCode()
    }

    /** A GATT read operation completed, recorded so every session byte is auditable. */
    data class GattRead(
        override val deviceId: String,
        override val timestamp: Instant,
        val characteristic: BleCharacteristic,
        val data: ByteArray,
    ) : DeviceEvent {
        override fun equals(other: Any?): Boolean = other is GattRead &&
            deviceId == other.deviceId && timestamp == other.timestamp && characteristic == other.characteristic && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * (31 * (31 * deviceId.hashCode() + timestamp.hashCode()) + characteristic.hashCode()) + data.contentHashCode()
    }

    /** A GATT write operation was issued, recorded for the packet monitor. */
    data class GattWritten(
        override val deviceId: String,
        override val timestamp: Instant,
        val characteristic: BleCharacteristic,
        val data: ByteArray,
        val withResponse: Boolean,
    ) : DeviceEvent {
        override fun equals(other: Any?): Boolean = other is GattWritten &&
            deviceId == other.deviceId && timestamp == other.timestamp && characteristic == other.characteristic &&
            withResponse == other.withResponse && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * (31 * (31 * (31 * deviceId.hashCode() + timestamp.hashCode()) + characteristic.hashCode()) + withResponse.hashCode()) + data.contentHashCode()
    }

    data class ActionStarted(override val deviceId: String, override val timestamp: Instant, val action: DeviceAction) : DeviceEvent
    data class ActionCompleted(override val deviceId: String, override val timestamp: Instant, val action: DeviceAction, val result: ActionResult.Success) : DeviceEvent
    data class ActionFailed(override val deviceId: String, override val timestamp: Instant, val action: DeviceAction, val error: RuntimeError) : DeviceEvent
    data class ScriptEmitted(override val deviceId: String, override val timestamp: Instant, val name: String, val value: StateValue) : DeviceEvent
    data class Error(override val deviceId: String, override val timestamp: Instant, val error: RuntimeError) : DeviceEvent
}

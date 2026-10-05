package com.fusion.melodyLinkNeo.core.activity

import com.fusion.melodyLinkNeo.core.logging.LogCategory
import com.fusion.melodyLinkNeo.core.logging.LogEntry
import com.fusion.melodyLinkNeo.core.logging.Logger
import com.fusion.melodyLinkNeo.device.runtime.DeviceEvent
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/** What a Home "最近活动" row represents. */
enum class ActivityKind { CONNECTION, GATT, NOTIFICATION, PACKAGE, HOOK, ERROR }

data class ActivityEntry(
    val id: Long,
    val timestamp: Instant,
    val kind: ActivityKind,
    val title: String,
    val detail: String? = null,
    val deviceId: String? = null,
    val deviceLabel: String? = null,
    val packageId: String? = null,
)

/**
 * Bounded activity stream for the Home page (DEVICE_CENTER_UI_PLAN §3.2).
 *
 * Two sources are merged:
 *  - the existing process-wide [Logger] (scan/connect/GATT/notification lines from the transport);
 *  - explicit `record(...)` calls for events the transport does not log (package installs,
 *    hook/anchor changes) and `recordEvent(...)` for live session events.
 *
 * A device-id to display-name map lets rows show "WF-1000XM3" instead of a MAC. Kept small and
 * in-memory; the plan's disk-backed ring arrives with the logging milestone.
 */
class ActivityRepository(
    private val logger: Logger,
    private val max: Int = 500,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val extras = MutableStateFlow<List<ActivityEntry>>(emptyList())
    private val deviceNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val counter = AtomicLong()

    val entries: Flow<List<ActivityEntry>> =
        combine(logger.entries, extras, deviceNames) { logs, recorded, names ->
            val fromLogs = logs.mapIndexed { index, entry -> entry.toEntry(index, names) }
            (fromLogs + recorded)
                .sortedByDescending { it.timestamp }
                .take(max)
        }

    /** Remembers a friendly name so later rows are readable. */
    fun registerDeviceName(deviceId: String, name: String?) {
        val label = name?.takeIf { it.isNotBlank() } ?: return
        deviceNames.update { current -> if (current[deviceId] == label) current else current + (deviceId to label) }
    }

    fun record(
        kind: ActivityKind,
        title: String,
        detail: String? = null,
        deviceId: String? = null,
        packageId: String? = null,
        timestamp: Instant = clock.instant(),
    ) {
        val entry = ActivityEntry(
            id = counter.incrementAndGet(),
            timestamp = timestamp,
            kind = kind,
            title = title,
            detail = detail,
            deviceId = deviceId,
            deviceLabel = deviceId?.let { deviceNames.value[it] },
            packageId = packageId,
        )
        extras.update { (it + entry).takeLast(max) }
    }

    /** Translates a live session event; notifications and raw GATT traffic are dropped as noise. */
    fun recordEvent(deviceId: String, event: DeviceEvent) {
        val entry = when (event) {
            is DeviceEvent.DeviceConnected -> ActivityEntry(
                counter.incrementAndGet(), event.timestamp, ActivityKind.CONNECTION, "连接设备",
                null, deviceId, deviceNames.value[deviceId],
            )
            is DeviceEvent.DeviceReady -> ActivityEntry(
                counter.incrementAndGet(), event.timestamp, ActivityKind.CONNECTION, "会话就绪",
                null, deviceId, deviceNames.value[deviceId],
            )
            is DeviceEvent.DeviceDisconnected -> ActivityEntry(
                counter.incrementAndGet(), event.timestamp, ActivityKind.CONNECTION, "断开连接",
                null, deviceId, deviceNames.value[deviceId],
            )
            is DeviceEvent.GattRead -> ActivityEntry(
                counter.incrementAndGet(), event.timestamp, ActivityKind.GATT, "读取特征",
                "${event.characteristic.uuid} · ${event.data.size} 字节", deviceId, deviceNames.value[deviceId],
            )
            is DeviceEvent.GattWritten -> ActivityEntry(
                counter.incrementAndGet(), event.timestamp, ActivityKind.GATT, "写入特征",
                "${event.characteristic.uuid} · ${event.data.size} 字节", deviceId, deviceNames.value[deviceId],
            )
            is DeviceEvent.ActionCompleted -> ActivityEntry(
                counter.incrementAndGet(), event.timestamp, ActivityKind.GATT, "执行动作",
                event.action.id, deviceId, deviceNames.value[deviceId],
            )
            is DeviceEvent.ActionFailed -> ActivityEntry(
                counter.incrementAndGet(), event.timestamp, ActivityKind.ERROR, "动作失败",
                "${event.action.id}: ${event.error.message}", deviceId, deviceNames.value[deviceId],
            )
            is DeviceEvent.Error -> ActivityEntry(
                counter.incrementAndGet(), event.timestamp, ActivityKind.ERROR, "会话错误",
                event.error.message, deviceId, deviceNames.value[deviceId],
            )
            else -> return
        }
        extras.update { (it + entry).takeLast(max) }
    }

    fun clearAll() {
        extras.value = emptyList()
        logger.clear()
    }

    /** Log rows have no stable id, so they take a negative index; recorded rows use a positive counter. */
    private fun LogEntry.toEntry(index: Int, names: Map<String, String>): ActivityEntry = ActivityEntry(
        id = -(index.toLong() + 1),
        timestamp = timestamp,
        kind = when (category) {
            LogCategory.SCAN, LogCategory.CONNECTION -> ActivityKind.CONNECTION
            LogCategory.GATT -> ActivityKind.GATT
            LogCategory.NOTIFICATION -> ActivityKind.NOTIFICATION
            LogCategory.ERROR -> ActivityKind.ERROR
        },
        title = category.toTitle(),
        detail = message,
        deviceId = deviceId,
        deviceLabel = deviceId?.let { names[it] },
    )

    private fun LogCategory.toTitle(): String = when (this) {
        LogCategory.SCAN -> "扫描设备"
        LogCategory.CONNECTION -> "连接设备"
        LogCategory.GATT -> "GATT 操作"
        LogCategory.NOTIFICATION -> "收到通知"
        LogCategory.ERROR -> "错误"
    }
}

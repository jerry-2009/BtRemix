package com.Fusion.Btremix.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Fusion.Btremix.core.logging.LogCategory
import com.Fusion.Btremix.core.logging.LogEntry
import com.Fusion.Btremix.core.settings.AppSettings
import com.Fusion.Btremix.core.settings.LogLevel
import com.Fusion.Btremix.core.settings.LogRetention
import com.Fusion.Btremix.ui.components.DcDivider
import com.Fusion.Btremix.ui.components.DcEmptyState
import com.Fusion.Btremix.ui.components.DcFilterChips
import com.Fusion.Btremix.ui.components.DcListGroup
import com.Fusion.Btremix.ui.components.DcListItem
import com.Fusion.Btremix.ui.components.DcSectionHeader
import com.Fusion.Btremix.ui.components.DcStatusDot
import com.Fusion.Btremix.ui.components.DcTopBar
import com.Fusion.Btremix.ui.theme.DcSpacing
import com.Fusion.Btremix.ui.theme.DcType
import com.Fusion.Btremix.ui.theme.statusColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LogsScreen(
    logs: List<LogEntry>,
    settings: AppSettings,
    onToggleLogging: (Boolean) -> Unit,
    onLogLevel: (LogLevel) -> Unit,
    onLogRetention: (LogRetention) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(ALL_LOGS) }
    val categories = listOf(ALL_LOGS) + LogCategory.entries.map { it.label }
    val visible = if (filter == ALL_LOGS) logs else logs.filter { it.category.label == filter }

    Column(modifier.fillMaxWidth()) {
        DcTopBar(
            title = "原始日志",
            onBack = onBack,
            actions = {
                IconButton(onClick = onClear) { Icon(Icons.Rounded.DeleteSweep, contentDescription = "清空") }
            },
        )
        Column(Modifier.padding(horizontal = DcSpacing.screenPadding)) {
            DcSectionHeader(title = "日志设置")
            DcListGroup {
                DcListItem(
                    title = "启用日志",
                    trailing = { Switch(checked = settings.loggingEnabled, onCheckedChange = onToggleLogging) },
                )
                DcDivider()
                EnumRow("日志级别", settings.logLevel.label, LogLevel.entries.map { it.label }) { label ->
                    LogLevel.entries.firstOrNull { it.label == label }?.let(onLogLevel)
                }
                DcDivider()
                EnumRow("日志保存时间", settings.logRetention.label, LogRetention.entries.map { it.label }) { label ->
                    LogRetention.entries.firstOrNull { it.label == label }?.let(onLogRetention)
                }
            }
            DcSectionHeader(title = "运行记录")
        }
        DcFilterChips(options = categories, selected = filter, onSelect = { filter = it })
        if (visible.isEmpty()) {
            DcEmptyState(title = "暂无日志", description = "启用日志并连接设备后，这里会显示运行记录")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = DcSpacing.screenPadding,
                    end = DcSpacing.screenPadding,
                    top = DcSpacing.md,
                    bottom = DcSpacing.contentBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(DcSpacing.xs),
            ) {
                items(visible.reversed()) { entry ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm),
                    ) {
                        DcStatusDot(color = entry.category.dotColor(), modifier = Modifier.padding(top = 6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                entry.category.label + (entry.deviceId?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            LOG_TIME.format(entry.timestamp.atZone(ZoneId.systemDefault())),
                            style = DcType.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogCategory.dotColor() = when (this) {
    LogCategory.ERROR -> MaterialTheme.statusColors.error
    LogCategory.NOTIFICATION -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.statusColors.idle
}

private const val ALL_LOGS = "全部"

/** Chinese labels for the log filter chips; the enum names are implementation detail. */
private val LogCategory.label: String
    get() = when (this) {
        LogCategory.SCAN -> "扫描"
        LogCategory.CONNECTION -> "连接"
        LogCategory.GATT -> "GATT"
        LogCategory.NOTIFICATION -> "通知"
        LogCategory.ERROR -> "错误"
    }

@Composable
private fun EnumRow(title: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    DcListItem(
        title = title,
        subtitle = value,
        trailing = {
            Row {
                options.forEach { option ->
                    TextButton(onClick = { onSelect(option) }) { Text(option) }
                }
            }
        },
    )
}

/** 关于模块: deliberately empty for now; the module-specific copy lands here later. */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        DcTopBar(title = "关于模块", onBack = onBack)
    }
}

/** Settings → 开发者 (D-UI-1): the developer tools moved here, hidden by default. */
@Composable
fun DeveloperScreen(
    developerMode: Boolean,
    onToggleDeveloperMode: (Boolean) -> Unit,
    onOpenMelodyDiagnostics: () -> Unit,
    onOpenExplorer: () -> Unit,
    onOpenStudio: () -> Unit,
    onOpenLogs: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        DcTopBar(title = "开发者", onBack = onBack)
        Column(Modifier.padding(horizontal = DcSpacing.screenPadding)) {
            DcSectionHeader(title = "开发者模式")
            DcListGroup {
                DcListItem(
                    title = "启用开发者模式",
                    subtitle = "在设备会话页显示原始状态与事件；入口保持可见",
                    trailing = { Switch(checked = developerMode, onCheckedChange = onToggleDeveloperMode) },
                )
            }
            DcSectionHeader(title = "工具")
            DcListGroup {
                DcListItem(title = "Melody 诊断", onClick = onOpenMelodyDiagnostics, trailing = { Text("→") })
                DcDivider()
                DcListItem(title = "BLE Explorer", onClick = onOpenExplorer, trailing = { Text("→") })
                DcDivider()
                DcListItem(title = "定义 Studio", onClick = onOpenStudio, trailing = { Text("→") })
                DcDivider()
                DcListItem(title = "原始日志", onClick = onOpenLogs, trailing = { Text("→") })
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = DcSpacing.lg))
        }
    }
}

private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

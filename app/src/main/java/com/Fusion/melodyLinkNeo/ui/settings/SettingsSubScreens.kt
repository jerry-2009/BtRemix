package com.fusion.melodyLinkNeo.ui.settings

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
import androidx.compose.material3.Text
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
import com.fusion.melodyLinkNeo.core.logging.LogCategory
import com.fusion.melodyLinkNeo.core.logging.LogEntry
import com.fusion.melodyLinkNeo.ui.components.DcEmptyState
import com.fusion.melodyLinkNeo.ui.components.DcFilterChips
import com.fusion.melodyLinkNeo.ui.components.DcListItem
import com.fusion.melodyLinkNeo.ui.components.DcSectionHeader
import com.fusion.melodyLinkNeo.ui.components.DcStatusDot
import com.fusion.melodyLinkNeo.ui.components.DcTopBar
import com.fusion.melodyLinkNeo.ui.theme.DcSpacing
import com.fusion.melodyLinkNeo.ui.theme.DcType
import com.fusion.melodyLinkNeo.ui.theme.statusColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LogsScreen(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf("全部") }
    val categories = listOf("全部") + LogCategory.entries.map { it.name }
    val visible = if (filter == "全部") logs else logs.filter { it.category.name == filter }

    Column(modifier.fillMaxWidth()) {
        DcTopBar(
            title = "日志",
            onBack = onBack,
            actions = {
                IconButton(onClick = onClear) { Icon(Icons.Rounded.DeleteSweep, contentDescription = "清空") }
            },
        )
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
                                entry.category.name + (entry.deviceId?.let { " · $it" } ?: ""),
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
    diagnosticsEnabled: Boolean,
    onToggleDiagnostics: (Boolean) -> Unit,
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
            com.fusion.melodyLinkNeo.ui.components.DcListGroup {
                DcListItem(
                    title = "启用开发者模式",
                    subtitle = "在设备会话页显示原始状态与事件；入口保持可见",
                    trailing = {
                        androidx.compose.material3.Switch(
                            checked = developerMode,
                            onCheckedChange = onToggleDeveloperMode,
                        )
                    },
                )
            }
            DcSectionHeader(title = "诊断")
            com.fusion.melodyLinkNeo.ui.components.DcListGroup {
                DcListItem(
                    title = "诊断采集",
                    subtitle = "记录宿主回传的结构化事件；宿主进程下次启动生效",
                    trailing = {
                        androidx.compose.material3.Switch(
                            checked = diagnosticsEnabled,
                            onCheckedChange = onToggleDiagnostics,
                        )
                    },
                )
            }
            DcSectionHeader(title = "工具")
            com.fusion.melodyLinkNeo.ui.components.DcListGroup {
                DcListItem(title = "BLE Explorer", onClick = onOpenExplorer, trailing = { Text("→") })
                com.fusion.melodyLinkNeo.ui.components.DcDivider()
                DcListItem(title = "定义 Studio", onClick = onOpenStudio, trailing = { Text("→") })
                com.fusion.melodyLinkNeo.ui.components.DcDivider()
                DcListItem(title = "原始日志", onClick = onOpenLogs, trailing = { Text("→") })
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = DcSpacing.lg))
        }
    }
}

private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

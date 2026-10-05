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
import com.fusion.melodyLinkNeo.ui.components.DcCard
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

@Composable
fun AboutScreen(appVersion: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        DcTopBar(title = "关于", onBack = onBack)
        Column(Modifier.padding(horizontal = DcSpacing.screenPadding)) {
            DcSectionHeader(title = "Device Center")
            DcCard {
                Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.xs)) {
                    Text("版本 $appVersion", style = DcType.mono)
                    Text(
                        "把声明式设备定义渲染成可操作的设备中心，并通过 ColorOS Melody 桥接模块把控制项投射到系统面板。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            DcSectionHeader(title = "开源许可")
            DcCard {
                Text(
                    "本应用基于 AndroidX / Jetpack Compose（Apache-2.0）构建，Liquid Glass 效果来自 io.github.kyant0:backdrop（Apache-2.0）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
fun UpdateSourceScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        DcTopBar(title = "更新源", onBack = onBack)
        Column(Modifier.padding(horizontal = DcSpacing.screenPadding)) {
            DcCard {
                Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.xs)) {
                    Text("未配置更新源", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "本期只支持本地安装 .dcpkg。配置更新源后可在这里填入索引地址，并在定义页检查更新。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Settings → 开发者 (D-UI-1): the developer tools moved here, hidden by default. */
@Composable
fun DeveloperScreen(
    developerMode: Boolean,
    onToggleDeveloperMode: (Boolean) -> Unit,
    onOpenExplorer: () -> Unit,
    onOpenStudio: () -> Unit,
    onOpenMelodyDiagnostics: () -> Unit,
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
            DcSectionHeader(title = "工具")
            com.fusion.melodyLinkNeo.ui.components.DcListGroup {
                DcListItem(title = "BLE Explorer", onClick = onOpenExplorer, trailing = { Text("→") })
                com.fusion.melodyLinkNeo.ui.components.DcDivider()
                DcListItem(title = "定义 Studio", onClick = onOpenStudio, trailing = { Text("→") })
                com.fusion.melodyLinkNeo.ui.components.DcDivider()
                DcListItem(title = "Melody 诊断", onClick = onOpenMelodyDiagnostics, trailing = { Text("→") })
                com.fusion.melodyLinkNeo.ui.components.DcDivider()
                DcListItem(title = "原始日志", onClick = onOpenLogs, trailing = { Text("→") })
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = DcSpacing.lg))
        }
    }
}

private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

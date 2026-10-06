package com.Fusion.Btremix.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.Fusion.Btremix.core.activity.ActivityEntry
import com.Fusion.Btremix.core.activity.ActivityKind
import com.Fusion.Btremix.core.hook.ModuleStatusUi
import com.Fusion.Btremix.core.hook.api.ModuleStatus
import com.Fusion.Btremix.ui.components.DcCard
import com.Fusion.Btremix.ui.components.DcEmptyState
import com.Fusion.Btremix.ui.components.DcLoadingState
import com.Fusion.Btremix.ui.components.DcRefreshButton
import com.Fusion.Btremix.ui.components.DcSectionHeader
import com.Fusion.Btremix.ui.components.DcStatTile
import com.Fusion.Btremix.ui.components.DcStatusDot
import com.Fusion.Btremix.ui.theme.DcSpacing
import com.Fusion.Btremix.ui.theme.DcType
import com.Fusion.Btremix.ui.theme.statusColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onQuickRestartScope: () -> Unit,
    onDexAdapt: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenDefinitions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        // Expressive hero header: oversized wordmark instead of a compact 56dp app bar.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = DcSpacing.screenPadding,
                    end = DcSpacing.sm,
                    top = DcSpacing.md,
                    bottom = DcSpacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("BtRemix", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "蓝牙设备管理中心",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                DcRefreshButton(onClick = { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("刷新") },
                        onClick = { menuOpen = false; onRefresh() },
                    )
                    DropdownMenuItem(
                        text = { Text("快速重启作用域") },
                        onClick = { menuOpen = false; onQuickRestartScope() },
                    )
                    DropdownMenuItem(
                        text = { Text("Dex 适配") },
                        onClick = { menuOpen = false; onDexAdapt() },
                    )
                }
            }
        }
        when {
            state.loading -> DcLoadingState(rows = 3)
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = DcSpacing.screenPadding,
                    end = DcSpacing.screenPadding,
                    bottom = DcSpacing.contentBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(DcSpacing.md),
            ) {
                item { state.moduleStatus?.let { ModuleStatusCard(it, onOpenDiagnostics) } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(DcSpacing.gridGutter)) {
                        DcStatTile(
                            value = state.activeSessions.toString(),
                            label = "活跃会话",
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            onClick = onOpenDevices,
                        )
                        DcStatTile(
                            value = state.connectedDevices.toString(),
                            label = "已连接设备",
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            onClick = onOpenDevices,
                        )
                        DcStatTile(
                            value = state.installedPackages.toString(),
                            label = "已安装包",
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            onClick = onOpenDefinitions,
                        )
                    }
                }
                item { DcSectionHeader(title = "最近活动") }
                if (state.activities.isEmpty()) {
                    item {
                        DcEmptyState(
                            title = "暂无活动",
                            description = "连接设备或安装设备包后，这里会显示最近的操作记录",
                        )
                    }
                } else {
                    items(state.activities, key = { it.id }) { entry -> ActivityRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun ModuleStatusCard(status: ModuleStatusUi, onOpenDetail: () -> Unit) {
    val colors = MaterialTheme.statusColors
    val color = when (status.status) {
        ModuleStatus.RUNNING -> colors.running
        ModuleStatus.WARNING -> colors.warning
        ModuleStatus.ERROR -> colors.error
        ModuleStatus.STOPPED -> colors.idle
    }
    val icon = when (status.status) {
        ModuleStatus.RUNNING -> Icons.Rounded.CheckCircle
        ModuleStatus.WARNING -> Icons.Rounded.HourglassTop
        ModuleStatus.ERROR -> Icons.Rounded.ErrorOutline
        ModuleStatus.STOPPED -> Icons.Rounded.RadioButtonUnchecked
    }
    DcCard {
        Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DcSpacing.md),
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(color.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(status.title, style = MaterialTheme.typography.titleLarge, color = color)
                    Text(
                        status.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
                    status.version?.let {
                        Text(it, style = DcType.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    status.coverage?.let {
                        Text(
                            "锚点 $it",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FilledTonalButton(onClick = onOpenDetail) { Text("查看详情") }
            }
        }
    }
}

@Composable
private fun ActivityRow(entry: ActivityEntry) {
    val dotColor = when (entry.kind) {
        ActivityKind.ERROR -> MaterialTheme.statusColors.error
        ActivityKind.CONNECTION -> MaterialTheme.statusColors.running
        ActivityKind.PACKAGE, ActivityKind.HOOK -> MaterialTheme.colorScheme.primary
        ActivityKind.GATT, ActivityKind.NOTIFICATION -> MaterialTheme.statusColors.idle
    }
    DcCard(contentPadding = PaddingValues(horizontal = DcSpacing.md, vertical = DcSpacing.sm)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm),
        ) {
            DcStatusDot(color = dotColor)
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                val detail = entry.deviceLabel ?: entry.detail
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                TIME_FORMAT.format(entry.timestamp.atZone(ZoneId.systemDefault())),
                style = DcType.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

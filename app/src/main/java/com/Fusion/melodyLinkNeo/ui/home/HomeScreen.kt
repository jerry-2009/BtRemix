package com.fusion.melodyLinkNeo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fusion.melodyLinkNeo.core.activity.ActivityEntry
import com.fusion.melodyLinkNeo.core.activity.ActivityKind
import com.fusion.melodyLinkNeo.core.hook.ModuleStatusUi
import com.fusion.melodyLinkNeo.core.hook.api.ModuleStatus
import com.fusion.melodyLinkNeo.ui.components.DcCard
import com.fusion.melodyLinkNeo.ui.components.DcEmptyState
import com.fusion.melodyLinkNeo.ui.components.DcLoadingState
import com.fusion.melodyLinkNeo.ui.components.DcRefreshButton
import com.fusion.melodyLinkNeo.ui.components.DcSectionHeader
import com.fusion.melodyLinkNeo.ui.components.DcStatTile
import com.fusion.melodyLinkNeo.ui.components.DcStatusDot
import com.fusion.melodyLinkNeo.ui.components.DcTopBar
import com.fusion.melodyLinkNeo.ui.theme.DcSpacing
import com.fusion.melodyLinkNeo.ui.theme.DcType
import com.fusion.melodyLinkNeo.ui.theme.statusColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenDefinitions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        DcTopBar(title = "Device Center", actions = { DcRefreshButton(onClick = onRefresh) })
        when {
            state.loading -> DcLoadingState(rows = 3)
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
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
                            onClick = onOpenDevices,
                        )
                        DcStatTile(
                            value = state.connectedDevices.toString(),
                            label = "已连接设备",
                            modifier = Modifier.weight(1f),
                            onClick = onOpenDevices,
                        )
                        DcStatTile(
                            value = state.installedPackages.toString(),
                            label = "已安装包",
                            modifier = Modifier.weight(1f),
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
        Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(status.title, style = MaterialTheme.typography.titleMedium, color = color)
                    Text(
                        status.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                status.version?.let { Text(it, style = DcType.mono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                status.coverage?.let { Text("锚点 $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = onOpenDetail) { Text("查看详情") }
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
    Row(
        Modifier.fillMaxWidth().padding(vertical = DcSpacing.xs),
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

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

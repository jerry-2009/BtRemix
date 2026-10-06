package com.Fusion.Btremix.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.Fusion.Btremix.device.runtime.DeviceLifecycleState
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.ui.components.DcCard
import com.Fusion.Btremix.ui.components.DcErrorState
import com.Fusion.Btremix.ui.components.DcLoadingState
import com.Fusion.Btremix.ui.components.DcMonoRow
import com.Fusion.Btremix.ui.components.DcSectionHeader
import com.Fusion.Btremix.ui.components.DcStatusChip
import com.Fusion.Btremix.ui.components.DcTopBar
import com.Fusion.Btremix.ui.renderer.DefinitionDevicePage
import com.Fusion.Btremix.ui.theme.DcSpacing
import com.Fusion.Btremix.ui.theme.DcType
import com.Fusion.Btremix.ui.theme.statusColors

@Composable
fun DeviceSessionScreen(
    state: SessionUiState,
    developerMode: Boolean,
    onBack: () -> Unit,
    onAction: (com.Fusion.Btremix.device.runtime.DeviceAction) -> Unit,
    onClearActionError: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        DcTopBar(title = state.name ?: state.packageDisplayName ?: "设备会话", onBack = onBack)
        when {
            state.opening -> DcLoadingState(rows = 3)
            state.error != null -> Column(Modifier.padding(DcSpacing.screenPadding)) {
                DcErrorState(message = state.error, onRetry = onRetry)
            }
            else -> Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DcSpacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(DcSpacing.md),
            ) {
                SessionHeaderCard(state)
                state.actionError?.let { message ->
                    DcErrorState(message = message, onRetry = onClearActionError)
                }
                val definition = state.definition
                if (definition == null) {
                    DcCard {
                        Text(
                            "此设备没有匹配的设备包，未提供控制项。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    DefinitionDevicePage(
                        definition = definition,
                        state = state.state,
                        onAction = onAction,
                    )
                }
                if (developerMode) {
                    DeveloperSection(state)
                }
                // The shell hides the bottom bar on this page and applies the navigation-bar inset,
                // so the old "clear the glass bar" reserve is no longer needed.
                Spacer(Modifier.height(DcSpacing.lg))
            }
        }
    }
}

@Composable
private fun SessionHeaderCard(state: SessionUiState) {
    val statusColor = when (state.lifecycle) {
        DeviceLifecycleState.Ready, DeviceLifecycleState.RefreshingState, DeviceLifecycleState.Connected ->
            MaterialTheme.statusColors.running
        DeviceLifecycleState.Disconnected -> MaterialTheme.statusColors.idle
        is DeviceLifecycleState.Error -> MaterialTheme.statusColors.error
        else -> MaterialTheme.statusColors.warning
    }
    val statusLabel = when (state.lifecycle) {
        DeviceLifecycleState.Ready, DeviceLifecycleState.RefreshingState, DeviceLifecycleState.Connected -> "已连接"
        DeviceLifecycleState.Disconnected -> "未连接"
        is DeviceLifecycleState.Error -> "错误"
        else -> "连接中"
    }
    DcCard {
        Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm),
            ) {
                Text(
                    state.name ?: state.packageDisplayName ?: state.mac,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DcStatusChip(text = statusLabel, color = statusColor)
            }
            listOfNotNull(state.author, state.deviceType).takeIf { it.isNotEmpty() }?.let { parts ->
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("会话时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatElapsed(state.elapsedSeconds), style = DcType.mono)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("设备地址", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.mac, style = DcType.mono)
                }
            }
        }
    }
}

@Composable
private fun DeveloperSection(state: SessionUiState) {
    DcSectionHeader(title = "开发者")
    DcCard {
        Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.xs)) {
            if (state.state.isEmpty()) {
                Text("暂无状态", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.state.forEach { (key, entry) ->
                    DcMonoRow(label = key, value = entry.value.display())
                }
            }
            if (state.recentEvents.isNotEmpty()) {
                Text("最近事件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.recentEvents.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun StateValue.display(): String = when (this) {
    is StateValue.BytesValue -> value.joinToString(" ") { "%02X".format(it) }
    is StateValue.ListValue -> value.joinToString { it.display() }
    else -> toString().substringAfter("(").removeSuffix(")")
}

private fun formatElapsed(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, secs)
}

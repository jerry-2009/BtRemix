package com.fusion.melodyLinkNeo.ui.devices

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fusion.melodyLinkNeo.device.registry.DeviceConnectionState
import com.fusion.melodyLinkNeo.device.registry.DeviceEntry
import com.fusion.melodyLinkNeo.ui.components.DcEmptyState
import com.fusion.melodyLinkNeo.ui.components.DcErrorState
import com.fusion.melodyLinkNeo.ui.components.DcFilterChips
import com.fusion.melodyLinkNeo.ui.components.DcRefreshButton
import com.fusion.melodyLinkNeo.ui.components.DcStatusDot
import com.fusion.melodyLinkNeo.ui.components.DcTopBar
import com.fusion.melodyLinkNeo.ui.theme.DcSpacing
import com.fusion.melodyLinkNeo.ui.theme.statusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DevicesScreen(
    state: DevicesUiState,
    artworkFor: (String) -> ImageBitmap?,
    onFilter: (DeviceFilter) -> Unit,
    onRefresh: () -> Unit,
    onToggleScan: () -> Unit,
    onConnect: (DeviceEntry) -> Unit,
    onRequestPermission: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        DcTopBar(
            title = "设备",
            actions = {
                DcRefreshButton(onClick = onRefresh)
            },
        )
        DcFilterChips(
            options = DeviceFilter.entries.map { it.label },
            selected = state.filter.label,
            onSelect = { label -> DeviceFilter.entries.firstOrNull { it.label == label }?.let(onFilter) },
        )
        state.error?.let { message ->
            Box(Modifier.padding(DcSpacing.md)) {
                DcErrorState(message = message, onRetry = onDismissError)
            }
        }
        if (!state.permissionGranted) {
            PermissionCard(onRequestPermission)
        }
        if (state.devices.isEmpty()) {
            DcEmptyState(
                title = "暂无设备",
                description = if (state.permissionGranted) {
                    "安装并启用设备包后，已配对或已发现的设备会显示在这里"
                } else {
                    "授予蓝牙权限后可发现附近的设备"
                },
                actionLabel = if (state.permissionGranted) null else "授予权限",
                onAction = if (state.permissionGranted) null else onRequestPermission,
            )
        } else {
            LazyVerticalGrid(
                // Exactly two compact cards per row; a fixed count keeps the artwork small on
                // wide phones instead of stretching each card to fill the screen.
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = DcSpacing.screenPadding,
                    end = DcSpacing.screenPadding,
                    top = DcSpacing.md,
                    bottom = DcSpacing.contentBottomInset,
                ),
                horizontalArrangement = Arrangement.spacedBy(DcSpacing.gridGutter),
                verticalArrangement = Arrangement.spacedBy(DcSpacing.gridGutter),
            ) {
                items(state.devices, key = { it.key }) { entry ->
                    DeviceCard(
                        entry = entry,
                        artwork = rememberArtwork(artworkFor, entry.packageId),
                        onClick = { onConnect(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DcSpacing.screenPadding, vertical = DcSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(DcSpacing.xs),
    ) {
        Text("需要蓝牙权限", style = MaterialTheme.typography.titleMedium)
        Text(
            "扫描与连接设备需要 BLUETOOTH_SCAN 与 BLUETOOTH_CONNECT；已安装的设备包列表仍然可见。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRequestPermission) { Text("授权") }
    }
}

@Composable
private fun DeviceCard(entry: DeviceEntry, artwork: ImageBitmap?, onClick: () -> Unit) {
    val statusColor = when (entry.state) {
        DeviceConnectionState.CONNECTED -> MaterialTheme.statusColors.running
        DeviceConnectionState.CONNECTING -> MaterialTheme.statusColors.warning
        DeviceConnectionState.ERROR -> MaterialTheme.statusColors.error
        DeviceConnectionState.DISCONNECTED -> MaterialTheme.statusColors.idle
    }
    val statusLabel = when (entry.state) {
        DeviceConnectionState.CONNECTED -> "已连接"
        DeviceConnectionState.CONNECTING -> "连接中"
        DeviceConnectionState.ERROR -> "连接失败"
        DeviceConnectionState.DISCONNECTED -> "未连接"
    }
    com.fusion.melodyLinkNeo.ui.components.DcCard(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) {
                    Image(artwork, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Monogram(entry.packageDisplayName)
                }
            }
            Column(Modifier.padding(DcSpacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.name ?: entry.packageDisplayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(entry.author, entry.deviceType).joinToString(" · ").ifBlank { entry.packageId },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DcStatusDot(color = statusColor, label = statusLabel)
            }
        }
    }
}

@Composable
private fun Monogram(label: String) {
    val initial = label.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Loads artwork off the main thread; the provider caches, so repeated compositions are cheap. */
@Composable
fun rememberArtwork(provider: (String) -> ImageBitmap?, packageId: String): ImageBitmap? {
    val state by produceState<ImageBitmap?>(initialValue = null, packageId) {
        value = withContext(Dispatchers.IO) { provider(packageId) }
    }
    return state
}

package com.fusion.melodyLinkNeo.ui.definitions

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fusion.melodyLinkNeo.definition.packages.InstalledPackage
import com.fusion.melodyLinkNeo.ui.components.DcCard
import com.fusion.melodyLinkNeo.ui.components.DcEmptyState
import com.fusion.melodyLinkNeo.ui.components.DcErrorState
import com.fusion.melodyLinkNeo.ui.components.DcLoadingState
import com.fusion.melodyLinkNeo.ui.components.DcSectionHeader
import com.fusion.melodyLinkNeo.ui.components.DcStatusChip
import com.fusion.melodyLinkNeo.ui.components.DcTopBar
import com.fusion.melodyLinkNeo.ui.theme.DcSpacing
import com.fusion.melodyLinkNeo.ui.theme.DcType

@Composable
fun DefinitionsScreen(
    state: DefinitionsUiState,
    onImport: (Uri) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpenDetail: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onConfirmInstall: () -> Unit,
    onConfirmReplace: () -> Unit,
    onDismissMessage: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }
    Column(modifier.fillMaxSize()) {
        DcTopBar(
            title = "定义",
            actions = {
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Rounded.Add, contentDescription = "导入设备包")
                }
            },
        )
        Column(Modifier.padding(horizontal = DcSpacing.screenPadding), verticalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
                FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  安装设备包")
                }
                OutlinedButton(onClick = onCheckUpdate) { Text("更新源") }
            }
            Text(
                "已安装设备包 ${state.packages.size} 个 · ${state.packages.count { it.enabled }} 启用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { message ->
            Box(Modifier.padding(DcSpacing.md)) { DcErrorState(message = message, onRetry = onDismissError) }
        }
        state.message?.let { message ->
            Box(Modifier.padding(horizontal = DcSpacing.md)) {
                DcCard { Text(message, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        when {
            state.loading -> DcLoadingState(rows = 3)
            state.packages.isEmpty() -> DcEmptyState(
                title = "还没有设备包",
                description = "安装 .dcpkg 后，匹配的设备会出现在设备页",
                actionLabel = "安装设备包",
                onAction = { picker.launch(arrayOf("*/*")) },
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = DcSpacing.screenPadding,
                    end = DcSpacing.screenPadding,
                    top = DcSpacing.md,
                    bottom = DcSpacing.contentBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(DcSpacing.sm),
            ) {
                item { DcSectionHeader(title = "已安装设备包") }
                items(state.packages, key = { it.packageId }) { pkg ->
                    PackageCard(
                        pkg = pkg,
                        onToggle = { enabled -> onToggle(pkg.packageId, enabled) },
                        onOpenDetail = { onOpenDetail(pkg.packageId) },
                        onUninstall = { onUninstall(pkg.packageId) },
                        onCheckUpdate = onCheckUpdate,
                    )
                }
            }
        }
    }
    if (state.stage != InstallStage.IDLE) {
        InstallPackageSheet(
            state = state,
            onDismiss = onDismissMessage,
            onDismissError = onDismissError,
            onConfirmInstall = onConfirmInstall,
            onConfirmReplace = onConfirmReplace,
        )
    }
}

@Composable
private fun PackageCard(
    pkg: InstalledPackage,
    onToggle: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
    onUninstall: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    DcCard(onClick = onOpenDetail) {
        Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
                Icon(
                    Icons.Rounded.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(pkg.devicePackage.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(pkg.devicePackage.definition.manifest.author, pkg.packageId).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("v${pkg.devicePackage.version}", style = DcType.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("查看详情") }, onClick = { menuOpen = false; onOpenDetail() })
                        DropdownMenuItem(text = { Text("检查更新") }, onClick = { menuOpen = false; onCheckUpdate() })
                        if (!pkg.isBuiltIn) {
                            DropdownMenuItem(text = { Text("卸载") }, onClick = { menuOpen = false; onUninstall() })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
                if (pkg.isBuiltIn) DcStatusChip(text = "内置", color = MaterialTheme.colorScheme.primary)
                if (!pkg.enabled) DcStatusChip(text = "已禁用", color = MaterialTheme.colorScheme.outline)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Switch(checked = pkg.enabled, onCheckedChange = onToggle)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallPackageSheet(
    state: DefinitionsUiState,
    onDismiss: () -> Unit,
    onDismissError: () -> Unit,
    onConfirmInstall: () -> Unit,
    onConfirmReplace: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DcSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(DcSpacing.sm),
        ) {
            Text("安装设备包", style = MaterialTheme.typography.titleLarge)
            when (state.stage) {
                InstallStage.READING -> {
                    Text("校验中…", style = MaterialTheme.typography.bodyMedium)
                    com.fusion.melodyLinkNeo.ui.components.DcSkeletonBlock(height = 20.dp)
                    com.fusion.melodyLinkNeo.ui.components.DcSkeletonBlock(height = 14.dp)
                }
                InstallStage.PREVIEW, InstallStage.CONFLICT -> state.preview?.let { preview ->
                    PreviewContent(preview, conflict = state.stage == InstallStage.CONFLICT)
                    Row(horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
                        if (state.stage == InstallStage.CONFLICT) {
                            TextButton(onClick = onDismiss) { Text("取消") }
                            FilledTonalButton(onClick = onConfirmReplace) { Text("替换") }
                        } else {
                            TextButton(onClick = onDismiss) { Text("取消") }
                            FilledTonalButton(onClick = onConfirmInstall) { Text("安装") }
                        }
                    }
                }
                InstallStage.INSTALLING -> Text("安装中…", style = MaterialTheme.typography.bodyMedium)
                InstallStage.DONE -> Text(state.message ?: "安装成功", style = MaterialTheme.typography.bodyMedium)
                InstallStage.ERROR -> {
                    DcErrorState(message = state.error ?: "安装失败", onRetry = onDismissError)
                }
                InstallStage.IDLE -> Unit
            }
            Spacer(Modifier.height(DcSpacing.lg))
        }
    }
}

@Composable
private fun PreviewContent(preview: PackagePreviewUi, conflict: Boolean) {
    DcCard {
        Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.xs)) {
            Text(preview.displayName, style = MaterialTheme.typography.titleMedium)
            Text("${preview.packageId} · v${preview.version}", style = DcType.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            preview.author?.let { Text("作者 $it", style = MaterialTheme.typography.bodySmall) }
            preview.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("能力 ${preview.capabilities.size} · 匹配规则 ${preview.matcherCount} · 资源 ${preview.assetCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (conflict) {
                Text(
                    "⚠ 已安装 v${preview.existingVersion}，是否替换？",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

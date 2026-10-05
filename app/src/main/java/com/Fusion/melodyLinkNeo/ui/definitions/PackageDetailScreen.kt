package com.fusion.melodyLinkNeo.ui.definitions

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fusion.melodyLinkNeo.definition.matcher.DefinitionMatcher
import com.fusion.melodyLinkNeo.definition.packages.InstalledPackage
import com.fusion.melodyLinkNeo.ui.components.DcCard
import com.fusion.melodyLinkNeo.ui.components.DcEmptyState
import com.fusion.melodyLinkNeo.ui.components.DcMonoRow
import com.fusion.melodyLinkNeo.ui.components.DcSectionHeader
import com.fusion.melodyLinkNeo.ui.components.DcStatusChip
import com.fusion.melodyLinkNeo.ui.components.DcTopBar
import com.fusion.melodyLinkNeo.ui.theme.DcSpacing
import com.fusion.melodyLinkNeo.ui.theme.DcType
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Package detail (DEVICE_CENTER_UI_PLAN §5.5).
 *
 * Everything shown here comes from the loaded definition - matchers, capabilities and optional
 * metadata - so the page stays vendor-neutral.
 */
@Composable
fun PackageDetailScreen(
    packageId: String,
    packages: List<InstalledPackage>,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pkg = packages.firstOrNull { it.packageId == packageId }
    Column(modifier.fillMaxWidth()) {
        DcTopBar(title = "包详情", onBack = onBack)
        if (pkg == null) {
            DcEmptyState(title = "包已卸载或损坏", description = "返回定义页查看当前已安装的设备包")
            return@Column
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DcSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(DcSpacing.md),
        ) {
            HeaderCard(pkg)
            CapabilitySection(pkg)
            MatcherSection(pkg)
            MetadataSection(pkg)
            Row(horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
                OutlinedButton(onClick = { onToggle(!pkg.enabled) }) { Text(if (pkg.enabled) "禁用" else "启用") }
                if (!pkg.isBuiltIn) {
                    OutlinedButton(onClick = onUninstall) { Text("卸载") }
                }
            }
            Spacer(Modifier.height(DcSpacing.contentBottomInset))
        }
    }
}

@Composable
private fun HeaderCard(pkg: InstalledPackage) {
    val manifest = pkg.devicePackage.definition.manifest
    DcCard {
        Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.xs)) {
            Text(pkg.devicePackage.displayName, style = MaterialTheme.typography.titleLarge)
            Text("${pkg.packageId} · v${pkg.devicePackage.version}", style = DcType.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val subtitle = listOfNotNull(
                manifest.author,
                pkg.installedAt?.let { INSTALL_FORMAT.format(it.atZone(ZoneId.systemDefault())) + " 安装" },
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            manifest.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm)) {
                if (pkg.isBuiltIn) DcStatusChip(text = "内置", color = MaterialTheme.colorScheme.primary)
                if (pkg.enabled) {
                    DcStatusChip(text = "已启用", color = MaterialTheme.colorScheme.primary)
                } else {
                    DcStatusChip(text = "已禁用", color = MaterialTheme.colorScheme.outline)
                }
                manifest.deviceType?.let { DcStatusChip(text = it, color = MaterialTheme.colorScheme.secondary) }
            }
        }
    }
}

@Composable
private fun CapabilitySection(pkg: InstalledPackage) {
    DcSectionHeader(title = "提供的功能")
    val capabilities = CapabilityCatalog.describe(pkg.devicePackage.capabilities)
    if (capabilities.isEmpty()) {
        Text("此包未声明能力", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    DcCard {
        Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.xs)) {
            capabilities.forEach { capability ->
                Text(capability.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MatcherSection(pkg: InstalledPackage) {
    DcSectionHeader(title = "支持的设备")
    val matchers = pkg.devicePackage.definition.manifest.matchers
    if (matchers.isEmpty()) {
        Text("未声明匹配规则", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    DcCard {
        Column(verticalArrangement = Arrangement.spacedBy(DcSpacing.xs)) {
            matchers.forEach { rule ->
                Text(
                    DefinitionMatcher.label(rule),
                    style = DcType.mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MetadataSection(pkg: InstalledPackage) {
    DcSectionHeader(title = "更新信息")
    DcCard {
        Column {
            DcMonoRow(label = "当前版本", value = "v${pkg.devicePackage.version}")
            val manifest = pkg.devicePackage.definition.manifest
            manifest.minRuntime?.let { DcMonoRow(label = "最低运行时", value = it) }
            manifest.homepage?.let { DcMonoRow(label = "主页", value = it) }
            DcMonoRow(label = "包格式", value = "v${pkg.devicePackage.packageFormat}")
        }
    }
}

private val INSTALL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

package com.fusion.melodyLinkNeo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fusion.melodyLinkNeo.core.settings.AppSettings
import com.fusion.melodyLinkNeo.core.settings.LogLevel
import com.fusion.melodyLinkNeo.core.settings.LogRetention
import com.fusion.melodyLinkNeo.core.settings.ThemeMode
import com.fusion.melodyLinkNeo.ui.components.DcDivider
import com.fusion.melodyLinkNeo.ui.components.DcHGap
import com.fusion.melodyLinkNeo.ui.components.DcListGroup
import com.fusion.melodyLinkNeo.ui.components.DcListItem
import com.fusion.melodyLinkNeo.ui.components.DcSectionHeader
import com.fusion.melodyLinkNeo.ui.components.DcTopBar
import com.fusion.melodyLinkNeo.ui.theme.DcSpacing

@Composable
fun SettingsScreen(
    settings: AppSettings,
    version: String,
    onToggleLogging: (Boolean) -> Unit,
    onLogLevel: (LogLevel) -> Unit,
    onLogRetention: (LogRetention) -> Unit,
    onOpenMelodyDex: () -> Unit,
    onStartOnBoot: (Boolean) -> Unit,
    onAutoRestoreSession: (Boolean) -> Unit,
    onBackgroundRun: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onAbout: () -> Unit,
    onDeveloper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        DcTopBar(title = "设置")
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DcSpacing.screenPadding),
        ) {
            DcSectionHeader(title = "日志")
            DcListGroup {
                SwitchRow("启用日志", settings.loggingEnabled, onToggleLogging)
                DcDivider()
                EnumRow("日志级别", settings.logLevel.label, LogLevel.entries.map { it.label }) { label ->
                    LogLevel.entries.firstOrNull { it.label == label }?.let(onLogLevel)
                }
                DcDivider()
                EnumRow("日志保存时间", settings.logRetention.label, LogRetention.entries.map { it.label }) { label ->
                    LogRetention.entries.firstOrNull { it.label == label }?.let(onLogRetention)
                }
            }

            DcSectionHeader(title = "Melody")
            DcListGroup {
                NavRow("Melody Dex 定位", onClick = onOpenMelodyDex)
            }

            DcSectionHeader(title = "模块行为")
            DcListGroup {
                SwitchRow("开机启动", settings.startOnBoot, onStartOnBoot)
                DcDivider()
                SwitchRow("自动恢复会话", settings.autoRestoreSession, onAutoRestoreSession)
                DcDivider()
                SwitchRow("后台运行", settings.backgroundRun, onBackgroundRun)
            }

            DcSectionHeader(title = "外观")
            DcListGroup {
                SwitchRow("动态取色（跟随壁纸）", settings.dynamicColor, onDynamicColor)
                DcDivider()
                Column {
                    Text(
                        "主题",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = DcSpacing.md, top = DcSpacing.sm),
                    )
                    Text(
                        "当前：${settings.themeMode.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = DcSpacing.md),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = DcSpacing.md, vertical = DcSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(DcSpacing.sm),
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            androidx.compose.material3.TextButton(onClick = { onThemeMode(mode) }) { Text(mode.label) }
                        }
                    }
                }
            }

            DcSectionHeader(title = "关于")
            DcListGroup {
                DcListItem(title = version)
                DcDivider()
                NavRow("关于模块", onClick = onAbout)
                DcDivider()
                NavRow("开发者", onClick = onDeveloper)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(DcSpacing.contentBottomInset))
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DcListItem(
        title = title,
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun EnumRow(title: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    Column {
        DcListItem(
            title = title,
            subtitle = value,
            trailing = {
                Row {
                    options.forEach { option ->
                        androidx.compose.material3.TextButton(onClick = { onSelect(option) }) { Text(option) }
                    }
                }
            },
        )
    }
}

@Composable
private fun NavRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    DcListItem(title = title, subtitle = subtitle, onClick = onClick, trailing = { Text("→") })
}

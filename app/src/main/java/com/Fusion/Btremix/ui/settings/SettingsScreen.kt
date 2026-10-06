package com.Fusion.Btremix.ui.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Fusion.Btremix.core.settings.AppSettings
import com.Fusion.Btremix.core.settings.ThemeMode
import com.Fusion.Btremix.ui.components.DcDivider
import com.Fusion.Btremix.ui.components.DcListGroup
import com.Fusion.Btremix.ui.components.DcListItem
import com.Fusion.Btremix.ui.components.DcSectionHeader
import com.Fusion.Btremix.ui.components.DcTopBar
import com.Fusion.Btremix.ui.theme.DcSpacing

@Composable
fun SettingsScreen(
    settings: AppSettings,
    version: String,
    onStartOnBoot: (Boolean) -> Unit,
    onAutoSessionOnBluetoothConnect: (Boolean) -> Unit,
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
            DcSectionHeader(title = "模块行为")
            DcListGroup {
                SwitchRow("开机启动", settings.startOnBoot, onStartOnBoot)
                DcDivider()
                SwitchRow(
                    "蓝牙连接时自动建立会话",
                    settings.autoSessionOnBluetoothConnect,
                    onAutoSessionOnBluetoothConnect,
                )
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
private fun NavRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    DcListItem(title = title, subtitle = subtitle, onClick = onClick, trailing = { Text("→") })
}

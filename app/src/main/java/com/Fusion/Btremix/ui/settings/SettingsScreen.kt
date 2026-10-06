package com.Fusion.Btremix.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    onReduceTransparency: (Boolean) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onAbout: () -> Unit,
    onDeveloper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        DcTopBar(title = "设置", subtitle = "模块行为、外观与关于")
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
                ThemeModeRow(current = settings.themeMode, onThemeMode = onThemeMode)
                DcDivider()
                // The switch is inverted: "底栏半透明" on == reduceTransparency off.
                SwitchRow(
                    "底栏半透明",
                    !settings.reduceTransparency,
                ) { translucent -> onReduceTransparency(!translucent) }
            }

            DcSectionHeader(title = "关于")
            DcListGroup {
                DcListItem(title = version)
                DcDivider()
                NavRow("关于模块", onClick = onAbout)
                DcDivider()
                NavRow("开发者", onClick = onDeveloper)
            }
            Spacer(Modifier.height(DcSpacing.contentBottomInset))
        }
    }
}

@Composable
private fun ThemeModeRow(current: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
    Column(Modifier.padding(horizontal = DcSpacing.md, vertical = DcSpacing.md)) {
        Text("主题", style = MaterialTheme.typography.bodyLarge)
        Text(
            "当前：${current.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(top = DcSpacing.sm),
        ) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == current,
                    onClick = { onThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    label = { Text(mode.label) },
                )
            }
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
    DcListItem(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            Text(
                "→",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

package com.Fusion.Btremix.ui.melody

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.Fusion.Btremix.BtRemixApplication
import com.Fusion.Btremix.BuildConfig
import com.Fusion.Btremix.melody.api.MelodyCallPolicy
import com.Fusion.Btremix.melody.bridge.MelodyDiagnosticStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Melody 诊断" (M5.4 D-28/D-29).
 *
 * Two jobs, both deliberately small: flip the host-side diagnostics switch (written into the module's
 * own preference file, read by the injected code at the next host process start), and export the
 * forwarded events through the system share sheet. The dump is plain `evt=` lines, so it is the same
 * text `adb logcat -s BtRemixMelody` would show.
 */
@Composable
fun MelodyDiagnosticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as? BtRemixApplication
    // The module preferences are the module app's own SharedPreferences; the injected code reads them
    // through the framework's remote-preference pipe (same file, so a plain write here is enough).
    val prefs = remember { context.getSharedPreferences(MODULE_PREFS, Context.MODE_PRIVATE) }
    // Default off (M5.4 D-29 revised): collecting is an explicit opt-in, off until someone asks.
    var enabled by remember { mutableStateOf(prefs.getBoolean(KEY_DIAGNOSTICS_ENABLED, false)) }
    var message by remember { mutableStateOf<String?>(null) }
    // Re-read after every action; the store is a plain in-memory ring, so the count is cheap.
    var buffered by remember { mutableStateOf(MelodyDiagnosticStore.size()) }
    var dropped by remember { mutableStateOf(MelodyDiagnosticStore.dropped()) }
    val managedMacs = app?.melodySupport?.managedMacsFlow?.collectAsState()?.value ?: emptyList()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Melody 诊断", style = MaterialTheme.typography.titleLarge)
        Text(
            "从 com.oplus.melody 进程回传的结构化事件（重定向 / 锚点 / 版本门控）。" +
                "默认关闭；打开后才开始采集，关掉会同时停掉宿主侧的日志格式化与回传，" +
                "把开销降到接近零。开关在宿主进程下次启动时生效。",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("诊断采集", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) "已开启" else "已关闭（默认）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    prefs.edit().putBoolean(KEY_DIAGNOSTICS_ENABLED, checked).apply()
                    MelodyDiagnosticStore.diagnosticsEnabled = checked
                    message = "已写入模块偏好；宿主进程下次启动生效"
                },
            )
        }

        Divider()
        KeyValue("宿主包", MelodyCallPolicy.HOST_PACKAGE)
        KeyValue("宿主版本", hostVersion(context) ?: "未安装 / 读不到")
        KeyValue("托管设备", if (managedMacs.isEmpty()) "无" else managedMacs.joinToString(", "))
        KeyValue("缓冲事件", "$buffered 条（已丢弃 $dropped 条）")
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Divider()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val content = buildExport(context, managedMacs)
                    MelodyDiagnosticStore.write(File(context.filesDir, DUMP_FILE_NAME), content)
                    share(context, content)
                    buffered = MelodyDiagnosticStore.size()
                    dropped = MelodyDiagnosticStore.dropped()
                    message = "已生成 ${buffered} 条事件；文件：${DUMP_FILE_NAME}"
                },
            ) { Text("导出并分享") }
            OutlinedButton(
                onClick = {
                    MelodyDiagnosticStore.clear()
                    buffered = 0
                    dropped = 0
                    message = "已清空缓冲"
                },
            ) { Text("清空") }
        }

        Text(
            "导出内容与 `adb logcat -s BtRemixMelody` 同格式；同时写入 files/$DUMP_FILE_NAME。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "注意：未开启“诊断采集”时宿主不产生这些事件，导出只会包含空缓冲与版本信息。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, Modifier.weight(0.35f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun buildExport(context: Context, managedMacs: List<String>): String {
    val app = context.applicationContext as? BtRemixApplication
    val managed = managedMacs.joinToString(" | ") { mac ->
        val device = app?.melodySupport?.support(mac)
        val range = device?.melody?.support?.hostVersions ?: "-"
        "$mac:${device?.definition?.manifest?.id ?: "?"}@${device?.definition?.manifest?.version ?: "?"}:$range"
    }
    return MelodyDiagnosticStore.render(
        linkedMapOf(
            "generatedAt" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()),
            "btremix" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "hostPackage" to MelodyCallPolicy.HOST_PACKAGE,
            "hostVersion" to (hostVersion(context) ?: "?"),
            "diagnosticsEnabled" to (MelodyDiagnosticStore.diagnosticsEnabled).toString(),
            "managedDefinitions" to (managed.ifEmpty { "-" }),
        ),
    )
}

private fun share(context: Context, content: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "BtRemix Melody 诊断")
        // A chooser forwards the extra over Binder; keep it well under the 1 MB transaction limit.
        putExtra(Intent.EXTRA_TEXT, content.take(MAX_SHARE_CHARS))
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, "导出 Melody 诊断").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun hostVersion(context: Context): String? = runCatching {
    context.packageManager.getPackageInfo(MelodyCallPolicy.HOST_PACKAGE, 0).versionName
}.getOrNull()

const val KEY_DIAGNOSTICS_ENABLED: String = "diagnostics_enabled"
const val DUMP_FILE_NAME: String = "melody-diagnostics.jsonl"
private const val MODULE_PREFS = "melody_bridge"
private const val MAX_SHARE_CHARS = 200_000

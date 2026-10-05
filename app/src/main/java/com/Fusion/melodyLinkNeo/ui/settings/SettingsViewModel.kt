package com.fusion.melodyLinkNeo.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fusion.melodyLinkNeo.BtRemixApplication
import com.fusion.melodyLinkNeo.core.logging.LogEntry
import com.fusion.melodyLinkNeo.core.settings.AppSettings
import com.fusion.melodyLinkNeo.core.settings.LogLevel
import com.fusion.melodyLinkNeo.core.settings.LogRetention
import com.fusion.melodyLinkNeo.core.settings.ThemeMode
import com.fusion.melodyLinkNeo.core.settings.UpdateInterval
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings + Logs state (DEVICE_CENTER_UI_PLAN §5.6). */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BtRemixApplication

    val settings: StateFlow<AppSettings> = app.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val logs: StateFlow<List<LogEntry>> = app.logger.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val moduleStatus = app.moduleStatus.status

    fun setDynamicColor(value: Boolean) = update { app.settings.setDynamicColor(value) }

    fun setThemeMode(value: ThemeMode) = update { app.settings.setThemeMode(value) }

    fun setLoggingEnabled(value: Boolean) = update { app.settings.setLoggingEnabled(value) }

    fun setLogLevel(value: LogLevel) = update { app.settings.setLogLevel(value) }

    fun setLogRetention(value: LogRetention) = update { app.settings.setLogRetention(value) }

    fun setAutoUpdate(value: UpdateInterval) = update { app.settings.setAutoUpdate(value) }

    fun setStartOnBoot(value: Boolean) = update { app.settings.setStartOnBoot(value) }

    fun setAutoRestoreSession(value: Boolean) = update { app.settings.setAutoRestoreSession(value) }

    /**
     * Turning "后台运行" off is the documented way to release a session that was kept alive after
     * leaving the session page (D-UI-3), so the background holds are dropped here.
     */
    fun setBackgroundRun(value: Boolean) = update {
        app.settings.setBackgroundRun(value)
        if (!value) app.sessionManager.releaseBackgroundHolds()
    }

    fun setDeveloperMode(value: Boolean) = update { app.settings.setDeveloperMode(value) }

    fun setReduceTransparency(value: Boolean) = update { app.settings.setReduceTransparency(value) }

    fun checkUpdateNow() {
        // Update sources are not implemented this milestone; keep the entry honest.
        app.activity.record(
            kind = com.fusion.melodyLinkNeo.core.activity.ActivityKind.PACKAGE,
            title = "检查更新",
            detail = "更新源未配置",
        )
    }

    fun clearLogs() = app.logger.clear()

    fun refreshModuleStatus() = app.moduleStatus.refresh()

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }
}

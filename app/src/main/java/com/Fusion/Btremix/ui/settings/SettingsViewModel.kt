package com.Fusion.Btremix.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.Fusion.Btremix.BtRemixApplication
import com.Fusion.Btremix.core.logging.LogEntry
import com.Fusion.Btremix.core.settings.AppSettings
import com.Fusion.Btremix.core.settings.LogLevel
import com.Fusion.Btremix.core.settings.LogRetention
import com.Fusion.Btremix.core.settings.ThemeMode
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

    fun setStartOnBoot(value: Boolean) = update { app.settings.setStartOnBoot(value) }

    /**
     * UI-only switch: the preference is persisted so the row keeps its state, but no auto-session
     * engine runs - flipping it on never starts a foreground service or asks for BLUETOOTH_CONNECT.
     */
    fun setAutoSessionOnBluetoothConnect(value: Boolean) = update {
        app.settings.setAutoSessionOnBluetoothConnect(value)
    }

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

    fun clearLogs() = app.logger.clear()

    fun refreshModuleStatus() = app.moduleStatus.refresh()

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }
}

package com.Fusion.Btremix.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.Fusion.Btremix.BtRemixApplication
import com.Fusion.Btremix.core.logging.LogEntry
import com.Fusion.Btremix.core.permissions.AndroidBluetoothPermissionManager
import com.Fusion.Btremix.core.permissions.PermissionStatus
import com.Fusion.Btremix.core.settings.AppSettings
import com.Fusion.Btremix.core.settings.LogLevel
import com.Fusion.Btremix.core.settings.LogRetention
import com.Fusion.Btremix.core.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings + Logs state (DEVICE_CENTER_UI_PLAN §5.6). */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BtRemixApplication
    private val permissionManager = AndroidBluetoothPermissionManager()

    val settings: StateFlow<AppSettings> = app.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val logs: StateFlow<List<LogEntry>> = app.logger.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val moduleStatus = app.moduleStatus.status

    /** Set when the switch was flipped on without BLUETOOTH_CONNECT, so the shell can ask for it. */
    private val mutableAutoSessionPermissionRequest = MutableStateFlow(false)
    val autoSessionPermissionRequest: StateFlow<Boolean> = mutableAutoSessionPermissionRequest.asStateFlow()

    /** Set when the runtime permission dialog came back denied, so the shell can toast. */
    private val mutableAutoSessionPermissionDenied = MutableStateFlow(false)
    val autoSessionPermissionDenied: StateFlow<Boolean> = mutableAutoSessionPermissionDenied.asStateFlow()

    fun setDynamicColor(value: Boolean) = update { app.settings.setDynamicColor(value) }

    fun setThemeMode(value: ThemeMode) = update { app.settings.setThemeMode(value) }

    fun setLoggingEnabled(value: Boolean) = update { app.settings.setLoggingEnabled(value) }

    fun setLogLevel(value: LogLevel) = update { app.settings.setLogLevel(value) }

    fun setLogRetention(value: LogRetention) = update { app.settings.setLogRetention(value) }

    fun setStartOnBoot(value: Boolean) = update { app.settings.setStartOnBoot(value) }

    fun requiredPermissions(): Array<String> =
        permissionManager.requiredPermissions(Build.VERSION.SDK_INT).toTypedArray()

    /**
     * The switch is the only place the auto-session foreground service may be started from
     * (HANDOFF_AUTO_SESSION.md §3): a Bluetooth broadcast is not a background-start exemption, so the
     * service has to be launched while the user is looking at this screen.
     *
     * Without `BLUETOOTH_CONNECT` the setting is not persisted - the switch stays off ("回弹") and
     * the shell is asked to run the runtime permission dialog first.
     */
    fun setAutoSessionOnBluetoothConnect(value: Boolean) = update {
        if (!value) {
            app.settings.setAutoSessionOnBluetoothConnect(false)
            app.stopAutoSessionService()
            return@update
        }
        if (hasBluetoothPermission()) {
            app.settings.setAutoSessionOnBluetoothConnect(true)
            app.startAutoSessionService()
        } else {
            app.settings.setAutoSessionOnBluetoothConnect(false)
            mutableAutoSessionPermissionRequest.value = true
        }
    }

    fun consumeAutoSessionPermissionRequest() {
        mutableAutoSessionPermissionRequest.value = false
    }

    fun consumeAutoSessionPermissionDenied() {
        mutableAutoSessionPermissionDenied.value = false
    }

    /** Result of the runtime permission dialog the shell launched for the switch. */
    fun onAutoSessionPermissionResult(granted: Boolean) {
        mutableAutoSessionPermissionRequest.value = false
        if (granted) {
            setAutoSessionOnBluetoothConnect(true)
        } else {
            mutableAutoSessionPermissionDenied.value = true
        }
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

    private fun hasBluetoothPermission(): Boolean = permissionManager.status(Build.VERSION.SDK_INT) { permission ->
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
    } == PermissionStatus.Granted

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }
}

package com.fusion.melodyLinkNeo.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Theme override from Settings → 外观. */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

enum class LogLevel(val label: String) {
    VERBOSE("详细"),
    INFO("信息"),
    WARN("警告"),
    ERROR("错误"),
}

enum class LogRetention(val days: Int, val label: String) {
    DAYS_7(7, "7 天"),
    DAYS_14(14, "14 天"),
    DAYS_30(30, "30 天"),
    FOREVER(0, "永久"),
}

enum class UpdateInterval(val label: String) {
    MANUAL("手动"),
    DAILY("每天"),
    WEEKLY("每周"),
    MONTHLY("每月"),
}

/** Persisted product settings (DEVICE_CENTER_UI_PLAN §3.6). */
data class AppSettings(
    val dynamicColor: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val loggingEnabled: Boolean = true,
    val logLevel: LogLevel = LogLevel.VERBOSE,
    val logRetention: LogRetention = LogRetention.DAYS_7,
    val autoUpdate: UpdateInterval = UpdateInterval.WEEKLY,
    val updateSource: String? = null,
    val startOnBoot: Boolean = true,
    val autoRestoreSession: Boolean = false,
    /** D-UI-3 exception: while on, leaving a device session keeps the connection alive. */
    val backgroundRun: Boolean = false,
    val developerMode: Boolean = false,
    val reduceTransparency: Boolean = false,
)

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "device_center_settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map(::toSettings)

    suspend fun setDynamicColor(value: Boolean) = putBoolean(Keys.DYNAMIC_COLOR, value)
    suspend fun setThemeMode(value: ThemeMode) = putString(Keys.THEME_MODE, value.name)
    suspend fun setLoggingEnabled(value: Boolean) = putBoolean(Keys.LOGGING_ENABLED, value)
    suspend fun setLogLevel(value: LogLevel) = putString(Keys.LOG_LEVEL, value.name)
    suspend fun setLogRetention(value: LogRetention) = putInt(Keys.LOG_RETENTION_DAYS, value.days)
    suspend fun setAutoUpdate(value: UpdateInterval) = putString(Keys.AUTO_UPDATE, value.name)
    suspend fun setUpdateSource(value: String?) = dataStore.edit { prefs ->
        if (value.isNullOrBlank()) prefs.remove(Keys.UPDATE_SOURCE) else prefs[Keys.UPDATE_SOURCE] = value
    }
    suspend fun setStartOnBoot(value: Boolean) = putBoolean(Keys.START_ON_BOOT, value)
    suspend fun setAutoRestoreSession(value: Boolean) = putBoolean(Keys.AUTO_RESTORE_SESSION, value)
    suspend fun setBackgroundRun(value: Boolean) = putBoolean(Keys.BACKGROUND_RUN, value)
    suspend fun setDeveloperMode(value: Boolean) = putBoolean(Keys.DEVELOPER_MODE, value)
    suspend fun setReduceTransparency(value: Boolean) = putBoolean(Keys.REDUCE_TRANSPARENCY, value)

    private suspend fun putBoolean(key: Preferences.Key<Boolean>, value: Boolean) =
        dataStore.edit { it[key] = value }

    private suspend fun putInt(key: Preferences.Key<Int>, value: Int) =
        dataStore.edit { it[key] = value }

    private suspend fun putString(key: Preferences.Key<String>, value: String) =
        dataStore.edit { it[key] = value }

    private fun toSettings(prefs: Preferences): AppSettings = AppSettings(
        dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
        themeMode = prefs[Keys.THEME_MODE]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
        loggingEnabled = prefs[Keys.LOGGING_ENABLED] ?: true,
        logLevel = prefs[Keys.LOG_LEVEL]?.let { name -> LogLevel.entries.firstOrNull { it.name == name } } ?: LogLevel.VERBOSE,
        logRetention = prefs[Keys.LOG_RETENTION_DAYS]?.let { days -> LogRetention.entries.firstOrNull { it.days == days } } ?: LogRetention.DAYS_7,
        autoUpdate = prefs[Keys.AUTO_UPDATE]?.let { name -> UpdateInterval.entries.firstOrNull { it.name == name } } ?: UpdateInterval.WEEKLY,
        updateSource = prefs[Keys.UPDATE_SOURCE],
        startOnBoot = prefs[Keys.START_ON_BOOT] ?: true,
        autoRestoreSession = prefs[Keys.AUTO_RESTORE_SESSION] ?: false,
        backgroundRun = prefs[Keys.BACKGROUND_RUN] ?: false,
        developerMode = prefs[Keys.DEVELOPER_MODE] ?: false,
        reduceTransparency = prefs[Keys.REDUCE_TRANSPARENCY] ?: false,
    )

    private object Keys {
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LOGGING_ENABLED = booleanPreferencesKey("logging_enabled")
        val LOG_LEVEL = stringPreferencesKey("log_level")
        val LOG_RETENTION_DAYS = intPreferencesKey("log_retention_days")
        val AUTO_UPDATE = stringPreferencesKey("auto_update")
        val UPDATE_SOURCE = stringPreferencesKey("update_source")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val AUTO_RESTORE_SESSION = booleanPreferencesKey("auto_restore_session")
        val BACKGROUND_RUN = booleanPreferencesKey("background_run")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val REDUCE_TRANSPARENCY = booleanPreferencesKey("reduce_transparency")
    }
}

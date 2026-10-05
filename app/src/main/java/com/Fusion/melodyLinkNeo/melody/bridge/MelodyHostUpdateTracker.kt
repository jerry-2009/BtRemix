package com.fusion.melodyLinkNeo.melody.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fusion.melodyLinkNeo.MainActivity
import com.fusion.melodyLinkNeo.R
import com.fusion.melodyLinkNeo.melody.api.MelodyAnchorPrefs
import com.fusion.melodyLinkNeo.melody.api.MelodyAnchorReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Melody was updated" detection + prompt (M6 §4).
 *
 * The trigger is the host `versionName` read through `PackageManager`. The *anchor fingerprint* lives
 * entirely on the host side (it is computed from `ApplicationInfo` at `onPackageLoaded`, where no
 * version string exists), so the app never fingerprints the host APK itself - it just compares the
 * version it can see with the version the last report was produced for. That keeps the two sides from
 * disagreeing about "which install is this" when the app cannot stat another package's APK.
 *
 * A state is published for the Compose banner/card; a system notification is posted once per version
 * (best effort - without `POST_NOTIFICATIONS` the in-app banner is all there is).
 */
object MelodyHostUpdateTracker {

    /** Extra on [MainActivity]: when it equals [PAGE_MELODY], open the "Melody" tab. */
    const val EXTRA_PAGE: String = "melody_page"
    const val PAGE_MELODY: String = "melody"

    private const val CHANNEL_ID = "melody_host_update"
    private const val CHANNEL_NAME = "Melody 宿主更新"
    private const val NOTIFICATION_ID = 0x4D36

    /** Marker written to [MelodyAnchorPrefs.SEEN_INSTALL] by [requestRescan]. */
    private const val RESCAN_PENDING = ""
    private const val HOST_PACKAGE = "com.oplus.melody"

    private val _state = MutableStateFlow<MelodyHostUpdateState?>(null)
    val state: StateFlow<MelodyHostUpdateState?> = _state.asStateFlow()

    /** Re-reads the host package and refreshes [state]; safe to call from the app start and the receiver. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(MelodyAnchorPrefs.GROUP, Context.MODE_PRIVATE)
        val info = runCatching { app.packageManager.getPackageInfo(HOST_PACKAGE, 0) }.getOrNull()
        if (info == null) {
            _state.value = null
            return
        }
        val version = info.versionName
        val seenVersion = prefs.getString(MelodyAnchorPrefs.SEEN_VERSION, null)
        val rescanPending = prefs.getString(MelodyAnchorPrefs.SEEN_INSTALL, null) == RESCAN_PENDING
        val report = MelodyAnchorStore.read(app)?.takeIf { it.hostPackage == HOST_PACKAGE }
        val reportVersion = prefs.getString(MelodyAnchorPrefs.REPORT_VERSION, null)

        val updateDetected = when {
            seenVersion == null -> {
                // First run on this device: record silently so a fresh install does not spam a prompt.
                prefs.edit().putString(MelodyAnchorPrefs.SEEN_VERSION, version).apply()
                false
            }
            rescanPending -> true
            else -> version != null && seenVersion != version
        }
        val awaitingHost = (updateDetected || report == null) &&
            (report == null || version == null || reportVersion != version)
        _state.value = MelodyHostUpdateState(
            hostInstalled = true,
            version = version,
            previousVersion = seenVersion?.takeIf { it != version },
            installId = report?.installId ?: "-",
            updateDetected = updateDetected,
            awaitingHost = awaitingHost,
            report = report,
            missingIds = MelodyAnchorStore.missingIds(report, null),
        )
        if (updateDetected) notifyOnce(app, prefs, version)
    }

    /** Marks the current host version as seen; called when the user opens/dismisses the prompt. */
    fun acknowledge(context: Context) {
        val app = context.applicationContext
        val current = _state.value ?: return
        app.getSharedPreferences(MelodyAnchorPrefs.GROUP, Context.MODE_PRIVATE)
            .edit()
            .putString(MelodyAnchorPrefs.SEEN_VERSION, current.version)
            .remove(MelodyAnchorPrefs.SEEN_INSTALL)
            .apply()
        _state.value = current.copy(updateDetected = false)
        cancelNotification(app)
    }

    /**
     * Drops the persisted anchor report and the seen version so the next host start re-scans with DexKit.
     * The prompt returns to the "waiting for the host" state.
     */
    fun requestRescan(context: Context) {
        val app = context.applicationContext
        MelodyAnchorStore.clear(app)
        app.getSharedPreferences(MelodyAnchorPrefs.GROUP, Context.MODE_PRIVATE)
            .edit()
            .putString(MelodyAnchorPrefs.SEEN_INSTALL, RESCAN_PENDING)
            .remove(MelodyAnchorPrefs.REPORT_VERSION)
            .remove(MelodyAnchorPrefs.NOTIFIED_VERSION)
            .apply()
        refresh(app)
    }

    /** Called by the receiver after a report was persisted; completes an "awaiting host" state. */
    fun onAnchorReport(context: Context) {
        refresh(context)
    }

    private fun notifyOnce(context: Context, prefs: android.content.SharedPreferences, version: String?) {
        val key = version ?: return
        if (prefs.getString(MelodyAnchorPrefs.NOTIFIED_VERSION, null) == key) return
        if (!canNotify(context)) return
        prefs.edit().putString(MelodyAnchorPrefs.NOTIFIED_VERSION, key).apply()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager, context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_PAGE, PAGE_MELODY)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (_state.value?.awaitingHost != false) {
            "检测到 Melody $key 已更新；宿主启动后自动用 DexKit 重新定位锚点。"
        } else {
            "检测到 Melody $key 已更新；已重新定位锚点 ${_state.value?.hits ?: 0}/${_state.value?.total ?: 0}。"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Melody 已更新")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelNotification(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    private fun canNotify(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(manager: NotificationManager, context: Context) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.app_name) + " Melody 宿主更新提示" },
        )
    }
}

/** Snapshot of the host install + the last anchor report, rendered by the banner and the Melody page. */
data class MelodyHostUpdateState(
    val hostInstalled: Boolean,
    val version: String?,
    val previousVersion: String?,
    val installId: String,
    val updateDetected: Boolean,
    /** True while the host has not reported for the installed version yet. */
    val awaitingHost: Boolean,
    val report: MelodyAnchorReport?,
    val missingIds: List<String>,
) {
    val hits: Int get() = report?.processes?.sumOf { it.hits } ?: 0
    val total: Int get() = report?.processes?.sumOf { it.total } ?: 0
    val resolved: Boolean get() = report != null && !awaitingHost
}

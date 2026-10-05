package com.fusion.melodyLinkNeo.device.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.fusion.melodyLinkNeo.BtRemixApplication
import com.fusion.melodyLinkNeo.R
import com.fusion.melodyLinkNeo.core.classic.api.ClassicConnectionMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground host for the auto-session feature (HANDOFF_AUTO_SESSION.md §6 step 4).
 *
 * The "蓝牙连接时自动建立会话" switch cannot be implemented with a manifest receiver: Android's
 * background-start exemptions do not include "woke up from a Bluetooth broadcast", so the FGS has to
 * be started from the user's tap on the switch (or while the app is in the foreground). It then owns
 * the [AutoSessionConnector] and the [ClassicConnectionMonitor] for as long as the switch is on.
 *
 * `startForeground` is wrapped like [com.fusion.melodyLinkNeo.melody.bridge.MelodySessionService]:
 * a `connectedDevice` foreground service needs `BLUETOOTH_CONNECT` to be usable *now*, and a start
 * that the system refuses must degrade to logging instead of killing the process.
 */
class AutoSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var monitor: ClassicConnectionMonitor? = null
    private var connector: AutoSessionConnector? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        ensureForeground()
        val app = application as BtRemixApplication
        val monitor = app.classicConnections
        this.monitor = monitor
        monitor.start(scope)
        scope.launch {
            val settings = runCatching { app.settings.settings.first() }.getOrNull()
            if (settings?.autoSessionOnBluetoothConnect != true) {
                Log.i(TAG, "auto.session.service_stopped_disabled")
                stopSelf()
                return@launch
            }
            // The service only exists while the switch is on, so seeding the flow with `true` avoids
            // a first-emission race that would drop an immediate link event.
            val enabled = app.settings.settings
                .map { it.autoSessionOnBluetoothConnect }
                .stateIn(scope, SharingStarted.Eagerly, true)
            val connector = app.createAutoSessionConnector(scope, monitor, enabled)
            this@AutoSessionService.connector = connector
            connector.start()
            // Catch up on links that were already up, then keep doing so on a slow timer. The timer
            // is the safety net for a broadcast we never received (for example a whole-adapter
            // power cycle); the ACL events remain the primary, immediate trigger.
            reconcile()
            while (isActive) {
                delay(RECONCILE_INTERVAL_MS)
                reconcile()
            }
        }
    }

    private suspend fun reconcile() {
        val app = application as? BtRemixApplication ?: return
        val monitor = monitor ?: return
        val connector = connector ?: return
        // Re-read the bonded list too: a whole-adapter power cycle empties `DeviceRegistry.devices`,
        // and the classic discovery poll would otherwise be the only thing bringing it back.
        runCatching { app.deviceRegistry.refresh() }
        runCatching { monitor.connectedDevices() }
            .onSuccess { devices -> connector.seed(devices) }
            .onFailure { Log.w(TAG, "auto.session.reconcile_failed: ${it.message}", it) }
    }

    /** The switch is the only starter; a system restart with nothing to do must not resurrect it. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connector?.stop()
        connector = null
        monitor?.stop()
        monitor = null
        // Release through the application scope, which outlives this service being torn down.
        (application as? BtRemixApplication)?.releaseAutoSessionHolds()
        if (foregroundStarted) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foregroundStarted = false
        }
        scope.cancel()
        Log.i(TAG, "auto.session.service_destroyed")
        super.onDestroy()
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        val result = runCatching {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        foregroundStarted = result.isSuccess
        if (result.isFailure) {
            Log.w(TAG, "auto.session.foreground_unavailable: ${result.exceptionOrNull()?.message}", result.exceptionOrNull())
            // A service started with startForegroundService() must reach the foreground within ~5s.
            // Without the notification there is nothing user-visible to keep running, so stop rather
            // than be killed by the timeout; the switch stays on and the next app start retries.
            stopSelf()
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.auto_session_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.auto_session_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.auto_session_notification_title))
            .setContentText(getString(R.string.auto_session_notification_text))
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "BtRemixAutoSession"
        private const val NOTIFICATION_ID = 0x4155 // "AU"
        private const val CHANNEL_ID = "auto_session"
        /** Matches the discovery/registry cadence; only a backstop for missed link broadcasts. */
        private const val RECONCILE_INTERVAL_MS = 30_000L
    }
}

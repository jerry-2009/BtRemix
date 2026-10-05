package com.fusion.melodyLinkNeo.melody.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.fusion.melodyLinkNeo.BtRemixApplication
import com.fusion.melodyLinkNeo.R
import com.fusion.melodyLinkNeo.melody.api.MelodyDoorbellProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Process-side host of the Melody bridge (MELODY_BRIDGE_SPEC §6.4, §12 M2b;
 * MELODY_BRIDGE_TRANSPORT_PLAN.md §7).
 *
 * It owns the [MelodyBridgeBinder], which is backed by the process-scoped
 * [com.fusion.melodyLinkNeo.device.session.SessionRegistry] the Compose UI already uses. That is what makes "both
 * front-ends share exactly one control channel" true by construction rather than by convention.
 *
 * Transport (§8.1): the host cannot bind to this service (package visibility), so the service no longer
 * waits to be bound. It is started by [BtRemixApplication] as soon as a session exists **or** a paired
 * device is claimed by a `melody` Definition (M3-D6 - support injection answers before any session), announces
 * its binder through [MelodyDoorbellSender], and stops itself once neither remains and no bound client is
 * left. `onBind` is kept as the in-process/same-UID contract used by instrumentation tests and by any future
 * explicit component path.
 *
 * The notification keeps the session alive while the panel is attached. Declaring the `connectedDevice`
 * type and calling `startForeground` can fail on a device state we do not control (permission revoked,
 * background-start restriction), so it is wrapped: the bridge then keeps working as an ordinary service
 * instead of crashing the process.
 */
class MelodySessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = MelodyBridgeLog()
    private val handler = Handler(Looper.getMainLooper())

    private var bridge: MelodyBridgeBinder? = null
    private var doorbell: MelodyDoorbellSender? = null
    private var sessionWatch: Job? = null
    private var foregroundStarted = false

    /** Bound clients (onBind/onRebind minus onUnbind); the service may only stop once this reaches 0. */
    private var boundClients = 0

    /** M5.4b: last hello-triggered ring, for [MelodyDoorbellProtocol.acceptsHello]. */
    private var lastHelloRingMs = 0L

    private val stopRunnable = Runnable { stopIfIdle(reason = "idle") }

    override fun onCreate() {
        super.onCreate()
        current = this
        setHelloReceiverEnabled(true)
        ensureForeground()
        watchSessions()
        startDoorbell()
    }

    /** Started (not bound) by the app; a system restart with no sessions must not resurrect the bridge. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder {
        cancelIdleStop()
        ensureForeground()
        boundClients += 1
        val binder = ensureBridge()
        log.event(
            "melody.bridge.service_bound",
            "sessions" to sessions().managedMacs().size,
            "clients" to boundClients,
        )
        return binder
    }

    /** Called when a client binds again before the idle stop fires; the binder instance is reused. */
    override fun onRebind(intent: Intent?) {
        cancelIdleStop()
        ensureForeground()
        boundClients += 1
        log.event("melody.bridge.service_rebound", "clients" to boundClients)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients = (boundClients - 1).coerceAtLeast(0)
        log.event("melody.bridge.service_unbound", "clients" to boundClients)
        // Returning true keeps the binder for the next bind so a panel restart does not pay for a new
        // service creation (and does not tear down the doorbell cadence).
        scheduleIdleStop()
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        doorbell?.stop()
        doorbell = null
        sessionWatch?.cancel()
        sessionWatch = null
        bridge?.close()
        bridge = null
        if (foregroundStarted) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foregroundStarted = false
        }
        scope.cancel()
        log.event("melody.bridge.service_destroyed")
        setHelloReceiverEnabled(false)
        current = null
        super.onDestroy()
    }

    /**
     * M5.4b: the injected host asked for a doorbell as soon as it came up. Answering immediately is the
     * whole point - a cold host would otherwise wait for the next keepalive tick (measured 6.9 s).
     * Returns whether a broadcast was actually queued.
     */
    fun ringDoorbellFromHost(): Boolean {
        if (doorbell == null) return false
        val now = SystemClock.elapsedRealtime()
        if (!MelodyDoorbellProtocol.acceptsHello(serviceAlive = true, elapsedSinceLastRingMs = now - lastHelloRingMs)) {
            return false
        }
        lastHelloRingMs = now
        handler.post { doorbell?.ringNow("host_hello") }
        return true
    }

    /**
     * The hello receiver is only reachable while this service runs, so it can never cold-start the
     * BtRemix process for nothing (M5.4b). Runs on the main thread from `onCreate`/`onDestroy`.
     */
    private fun setHelloReceiverEnabled(enabled: Boolean) {
        runCatching {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MelodyHostHelloReceiver::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { log.warn("melody.bridge.hello_receiver_toggle_failed", it) }
    }

    // --- internals ------------------------------------------------------------------------------

    private fun sessions() = (application as BtRemixApplication).sessions

    /**
     * Paired devices claimed by a `melody` Definition. The service has to stay up for them even
     * without a live session, because the host asks the whitelist before BtRemix ever connects
     * (M3-D6).
     */
    private fun melodyRegistry() = (application as BtRemixApplication).melodySupport

    private fun ensureBridge(): MelodyBridgeBinder {
        bridge?.let { return it }
        val app = application as BtRemixApplication
        val binder = MelodyBridgeBinder(
            context = this,
            sessions = sessions(),
            registry = app.melodySupport,
            projection = app.melodyProjection,
            log = log,
            scope = scope,
            // M5.4 D-31: a host process that just attached asks for one extra doorbell so the sibling
            // `:fg` process does not wait for the 30 s keepalive.
            onDoorbellRequested = { reason -> handler.post { doorbell?.ringNow(reason) } },
        )
        bridge = binder
        log.event("melody.bridge.service_binder_ready", "sessions" to sessions().managedMacs().size)
        return binder
    }

    /** The doorbell exists even before anybody binds: it is how the panel learns the binder exists. */
    private fun startDoorbell() {
        val sender = MelodyDoorbellSender(
            context = this,
            log = log,
            binderProvider = { ensureBridge() },
            clientAttached = { bridge?.hasClients() == true },
            senderName = applicationInfo.processName ?: packageName,
        )
        doorbell = sender
        sender.start()
    }

    /**
     * Stops the service once nothing needs it any more. A live session keeps it up (the whole point of the
     * bridge is to serve that session), and so does a bound client.
     */
    private fun watchSessions() {
        sessionWatch = scope.launch {
            combine(sessions().managedMacsFlow, melodyRegistry().managedMacsFlow) { live, managed ->
                live.size + managed.size
            }.collect { work ->
                handler.post {
                    if (work == 0) scheduleIdleStop() else cancelIdleStop()
                }
            }
        }
    }

    private fun scheduleIdleStop() {
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, IDLE_STOP_DELAY_MS)
    }

    private fun cancelIdleStop() {
        handler.removeCallbacks(stopRunnable)
    }

    private fun stopIfIdle(reason: String) {
        val live = sessions().managedMacs().size
        val managed = melodyRegistry().managedMacs().size
        if (live > 0 || managed > 0 || boundClients > 0) {
            log.event(
                "melody.bridge.service_idle_keep",
                "reason" to reason,
                "sessions" to live,
                "managed" to managed,
                "clients" to boundClients,
            )
            return
        }
        log.event("melody.bridge.service_idle_stop", "reason" to reason)
        stopSelf()
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        val result = runCatching {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        foregroundStarted = result.isSuccess
        val failure = result.exceptionOrNull() ?: return
        log.warn("melody.bridge.foreground_unavailable", failure)
        // A service started with startForegroundService() must reach the foreground within ~5s or the system
        // tears this process down (the connectedDevice type needs BLUETOOTH_CONNECT to be usable *now*, which
        // is not true when the start is not user-driven). With nothing to serve, stop instead of dying on
        // that timeout; a live session means the user connected a device, so the retry below is the real path.
        if (boundClients == 0 && sessions().managedMacs().isEmpty() && melodyRegistry().managedMacs().isEmpty()) {
            log.event("melody.bridge.service_stop_no_foreground")
            handler.post { stopSelf() }
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.melody_bridge_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.melody_bridge_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.melody_bridge_notification_title))
            .setContentText(getString(R.string.melody_bridge_notification_text))
            .setOngoing(true)
            .build()

    companion object {
        private const val NOTIFICATION_ID = 0x4D45 // "ME"
        private const val CHANNEL_ID = "melody_bridge"
        private const val IDLE_STOP_DELAY_MS = 5_000L

        /** Live service instance (same process as the receiver); `null` while the bridge is stopped. */
        @Volatile
        private var current: MelodySessionService? = null

        /**
         * M5.4b entry point used by [MelodyHostHelloReceiver]. Returns false when the bridge is not
         * running - the hello is then simply dropped, which is correct: there is no binder to hand out.
         */
        fun requestDoorbellFromHost(): Boolean =
            current?.ringDoorbellFromHost() ?: false
    }
}

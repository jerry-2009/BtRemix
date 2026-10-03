package com.Fusion.Btremix.melody.bridge

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that hosts the Melody bridge on the BtRemix side (MELODY_BRIDGE_SPEC §6.4).
 *
 * M0 only declares the component so the manifest, permissions and foreground-service type are
 * locked in before any hook depends on them. `onBind` intentionally returns `null`: the
 * `IMelodyBridge.Stub` binder, the UID check and the session wiring arrive in M2, and until then
 * nothing in the app starts this service.
 *
 * It must never be started by [com.Fusion.Btremix.BtRemixApplication], so a normal BtRemix launch is
 * unaffected by the bridge.
 */
class MelodySessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}

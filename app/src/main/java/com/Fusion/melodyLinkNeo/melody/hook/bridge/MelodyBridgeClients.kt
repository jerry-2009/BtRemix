package com.fusion.melodyLinkNeo.melody.hook.bridge

import android.content.Context
import com.fusion.melodyLinkNeo.melody.hook.MelodyLog

/**
 * Per-host-process holder for the single [MelodyBridgeClient] (MELODY_BRIDGE_SPEC §7.4).
 *
 * The panel hooks (M4) and the provider hooks (M3) must share one link and one cache; creating a client
 * per hook would open a second binder connection and lose the "same snapshot on both sides" property.
 * The injected process is long-lived, so a plain object is the right lifetime here.
 */
internal object MelodyBridgeClients {

    @Volatile
    private var client: MelodyBridgeClient? = null

    @Volatile
    private var probeStarted = false

    fun getOrCreate(context: Context, log: MelodyLog): MelodyBridgeClient = synchronized(this) {
        client ?: MelodyBridgeClient(context.applicationContext ?: context, log).also {
            client = it
            it.start()
        }
    }

    fun existing(): MelodyBridgeClient? = client

    /** Runs the M2b diagnostic probe at most once per host process. */
    fun probeOnce(client: MelodyBridgeClient, log: MelodyLog, processName: String?) {
        synchronized(this) {
            if (probeStarted) return
            probeStarted = true
        }
        MelodyBridgeProbe(client, log, processName).run()
    }
}

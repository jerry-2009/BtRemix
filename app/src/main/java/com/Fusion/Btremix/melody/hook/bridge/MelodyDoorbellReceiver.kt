package com.Fusion.Btremix.melody.hook.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import com.Fusion.Btremix.melody.api.MelodyDoorbellProtocol
import com.Fusion.Btremix.melody.hook.MelodyLog

/**
 * Host-process half of the doorbell (MELODY_BRIDGE_TRANSPORT_PLAN.md §4.2-1).
 *
 * Registered dynamically from `Application.onCreate`, so it depends on no host component, needs no host
 * permission and never triggers host logic. The cost is symmetric: a doorbell cannot wake a host process
 * that is not running, which matches the design (the bridge only matters while the panel or the provider
 * is actually using it, and the next keepalive covers a restart).
 *
 * `onReceive` only unpacks and validates the extras; the callback must return quickly and hand any binder
 * work to its own thread, because this runs on the host's main thread.
 */
internal class MelodyDoorbellReceiver(
    private val log: MelodyLog,
    private val onDoorbell: (binder: IBinder, generation: Int, protocol: Int, sender: String?) -> Unit,
) {

    private val filter = IntentFilter(MelodyDoorbellProtocol.ACTION)
    private var receiver: BroadcastReceiver? = null
    private var registeredWith: Context? = null

    @Synchronized
    fun register(context: Context) {
        if (receiver != null) return
        val impl = object : BroadcastReceiver() {
            override fun onReceive(source: Context?, intent: Intent?) = deliver(intent)
        }
        val registered = runCatching {
            // RECEIVER_EXPORTED is required on API 33+: the sender lives in another app, and without the
            // explicit flag the registration is refused (not silently ignored).
            context.registerReceiver(impl, filter, Context.RECEIVER_EXPORTED)
        }.onFailure {
            log.warn("melody.bridge.receiver_failed", it)
        }.isSuccess
        if (!registered) return
        receiver = impl
        registeredWith = context
        log.event(
            "melody.bridge.receiver_registered",
            "side" to SIDE,
            "action" to MelodyDoorbellProtocol.ACTION,
        )
    }

    @Synchronized
    fun unregister() {
        val current = receiver ?: return
        val context = registeredWith
        receiver = null
        registeredWith = null
        if (context != null) runCatching { context.unregisterReceiver(current) }
        log.event("melody.bridge.receiver_unregistered", "side" to SIDE)
    }

    private fun deliver(intent: Intent?) {
        if (intent == null || intent.action != MelodyDoorbellProtocol.ACTION) return
        val extras = intent.extras ?: return
        val binder = extras.getBinder(MelodyDoorbellProtocol.EXTRA_BRIDGE)
        val generation = extras.getInt(MelodyDoorbellProtocol.EXTRA_GENERATION, 0)
        val protocol = extras.getInt(MelodyDoorbellProtocol.EXTRA_PROTOCOL, 0)
        val sender = extras.getString(MelodyDoorbellProtocol.EXTRA_SENDER)
        log.event(
            "melody.bridge.doorbell_received",
            "side" to SIDE,
            "generation" to generation,
            "protocol" to protocol,
            "bridge" to (binder != null),
            "sender" to sender,
        )
        if (binder == null) {
            log.warn("melody.bridge.doorbell_empty")
            return
        }
        if (!MelodyDoorbellProtocol.isCompatible(protocol)) {
            log.event(
                "melody.bridge.doorbell_incompatible",
                "side" to SIDE,
                "protocol" to protocol,
                "supported" to MelodyDoorbellProtocol.VERSION,
            )
            return
        }
        runCatching { onDoorbell(binder, generation, protocol, sender) }
            .onFailure { log.warn("melody.bridge.doorbell_failed", it) }
    }

    private companion object {
        const val SIDE = "melody"
    }
}

package com.fusion.melodyLinkNeo.melody.bridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.fusion.melodyLinkNeo.melody.api.MelodyCallPolicy
import com.fusion.melodyLinkNeo.melody.api.MelodyDoorbellProtocol

/**
 * BtRemix half of the doorbell (MELODY_BRIDGE_TRANSPORT_PLAN.md §4.3, §7).
 *
 * Keeps re-offering the bridge binder in a package-restricted broadcast while the session service is
 * alive. It cannot know whether a host process is listening, so it uses a burst until the service reports
 * at least one registered listener and a 30s keepalive afterwards - the keepalive is what covers "host
 * process restarted after we did", and it is also what reaches the second host process (the panel's `:fg`
 * runs separately from the provider's main process).
 *
 * The broadcast itself carries no device data: MACs, state and definitions all travel over the binder, and
 * every binder call is still UID-checked, so a third app that somehow catches the doorbell only obtains a
 * proxy that refuses it.
 */
internal class MelodyDoorbellSender(
    private val context: Context,
    private val log: MelodyBridgeLog,
    private val binderProvider: () -> IBinder?,
    private val clientAttached: () -> Boolean,
    private val senderName: String,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {

    @Volatile
    private var running = false

    private var attempt = 0
    private var lastBinder: IBinder? = null
    private var revision = 0

    /**
     * Generation base. A new binder bumps [revision], and the base is derived from device uptime so a
     * restarted BtRemix process does not re-issue the previous process's generation numbers. A collision
     * is harmless (the client only dedupes while the old proxy is still alive), but distinct numbers keep
     * the two-sided logs readable.
     */
    private val base: Int = (SystemClock.elapsedRealtime() and 0x3FFF_FFFFL).toInt()

    private val ring = Runnable { ringDoorbell() }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        attempt = 0
        log.event(
            "melody.bridge.doorbell_start",
            "protocol" to MelodyDoorbellProtocol.VERSION,
            "sender" to senderName,
        )
        schedule(MelodyDoorbellProtocol.nextDelayMs(attempt = 1, clientAttached = false))
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(ring)
        log.event("melody.bridge.doorbell_stop")
    }

    /**
     * M5.4 D-31: one immediate extra broadcast, requested by a host process that just attached (the
     * `:fg` panel process would otherwise wait for the keepalive). Keeps the normal cadence intact -
     * the next scheduled ring is simply replaced by this earlier one.
     */
    @Synchronized
    fun ringNow(reason: String) {
        if (!running) return
        log.event("melody.bridge.doorbell_requested", "reason" to reason, "attempt" to attempt)
        ringDoorbell()
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(ring)
        handler.postDelayed(ring, delayMs)
    }

    private fun ringDoorbell() {
        if (!running) return
        attempt += 1
        val binder = binderProvider()
        if (binder == null) {
            // No binder yet (service still coming up): keep the cadence, do not spin.
            log.event("melody.bridge.doorbell_unavailable", "attempt" to attempt)
            schedule(MelodyDoorbellProtocol.nextDelayMs(attempt + 1, clientAttached = false))
            return
        }
        if (lastBinder !== binder) {
            lastBinder = binder
            revision += 1
        }
        val generation = base + revision
        val attached = runCatching { clientAttached() }.getOrDefault(false)
        val sent = runCatching { context.sendBroadcast(intent(binder, generation)) }
        if (sent.isSuccess) {
            log.event(
                "melody.bridge.doorbell_sent",
                "generation" to generation,
                "protocol" to MelodyDoorbellProtocol.VERSION,
                "attempt" to attempt,
                "client" to attached,
            )
        } else {
            log.warn("melody.bridge.doorbell_failed", sent.exceptionOrNull())
        }
        schedule(MelodyDoorbellProtocol.nextDelayMs(attempt + 1, attached))
    }

    private fun intent(binder: IBinder, generation: Int): Intent =
        Intent(MelodyDoorbellProtocol.ACTION)
            // setPackage keeps the doorbell out of every other app, including other OEM packages.
            .setPackage(MelodyCallPolicy.HOST_PACKAGE)
            // The host process is typically cached/background; ask for the foreground queue slot so the
            // panel sees the link immediately instead of after the background broadcast delay.
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtras(
                Bundle().apply {
                    putBinder(MelodyDoorbellProtocol.EXTRA_BRIDGE, binder)
                    putInt(MelodyDoorbellProtocol.EXTRA_GENERATION, generation)
                    putInt(MelodyDoorbellProtocol.EXTRA_PROTOCOL, MelodyDoorbellProtocol.VERSION)
                    putString(MelodyDoorbellProtocol.EXTRA_SENDER, senderName)
                },
            )
}

package com.Fusion.Btremix.melody.hook.bridge

import com.Fusion.Btremix.melody.hook.MelodyLog

/**
 * M2b acceptance probe (MELODY_BRIDGE_SPEC §12 M2b).
 *
 * Before the panel projection exists (M4), nothing on the Melody side would exercise the bridge, and a
 * transport that is never called cannot be validated on a device. This probe is the smallest honest
 * caller: it waits for the doorbell, lists the managed MACs and reads one snapshot, logging exactly the
 * events the milestone's acceptance criterion names ("both sides show the same snapshot", "no device
 * degrades gracefully" - see MELODY_BRIDGE_TRANSPORT_PLAN.md §11.3). It runs on its own thread so a slow or
 * absent BtRemix process never delays the host's `Application.onCreate`.
 *
 * A timeout is not a failure of the client: the receiver stays registered and the next keepalive doorbell
 * still connects it. The probe only records that at *this* moment there was no link
 * (`melody.bridge.doorbell_absent`), which is the documented no-session/not-running behaviour.
 */
internal class MelodyBridgeProbe(
    private val client: MelodyBridgeClient,
    private val log: MelodyLog,
    private val processName: String?,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    fun run() {
        val thread = Thread({ probe() }, "BtRemixMelodyProbe")
        thread.isDaemon = true
        thread.start()
    }

    private fun probe() {
        log.event("melody.bridge.probe.start", "side" to SIDE, "process" to processName)
        if (!client.awaitConnected(timeoutMs)) {
            log.event(
                "melody.bridge.doorbell_absent",
                "side" to SIDE,
                "process" to processName,
                "timeout_ms" to timeoutMs,
            )
            log.event("melody.bridge.probe.offline", "side" to SIDE, "process" to processName)
            return
        }
        val macs = client.managedMacs()
        log.event(
            "melody.bridge.probe.managed",
            "side" to SIDE,
            "process" to processName,
            "count" to macs.size,
            "macs" to macs.joinToString(","),
        )
        val first = macs.firstOrNull() ?: return
        val snapshot = client.snapshot(first)
        log.event(
            "melody.bridge.probe.snapshot",
            "side" to SIDE,
            "process" to processName,
            "mac" to first,
            "lifecycle" to snapshot?.lifecycle,
            "keys" to (snapshot?.state?.size() ?: 0),
        )
    }

    private companion object {
        const val SIDE = "melody"
        /** Long enough to catch the sender's burst (immediate/2s/5s) in the common "BtRemix is up" case. */
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}

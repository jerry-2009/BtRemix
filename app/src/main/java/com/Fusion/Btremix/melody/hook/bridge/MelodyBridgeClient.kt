package com.Fusion.Btremix.melody.hook.bridge

import android.content.Context
import android.os.DeadObjectException
import android.os.IBinder
import android.os.SystemClock
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.melody.api.MelodyBridgeCache
import com.Fusion.Btremix.melody.api.MelodyBridgeResult
import com.Fusion.Btremix.melody.api.MelodyBundleCodec
import com.Fusion.Btremix.melody.api.MelodyDoorbellProtocol
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.api.MelodySnapshot
import com.Fusion.Btremix.melody.api.MelodySupportInfo
import com.Fusion.Btremix.melody.bridge.IMelodyBridge
import com.Fusion.Btremix.melody.bridge.IMelodyBridgeListener
import com.Fusion.Btremix.melody.hook.MelodyLog
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Melody-side half of the bridge (MELODY_BRIDGE_SPEC §8, §12 M2b; MELODY_BRIDGE_TRANSPORT_PLAN.md §6).
 *
 * It owns exactly the responsibilities the spec assigns to the client: obtain the bridge, notice when it
 * dies, keep trying, keep a cold-start cache, and log every exchange with the same `evt=` shape the BtRemix
 * side uses. Nothing here knows about Definitions or BLE - it only forwards intent and reads snapshots,
 * which is what keeps control on the BtRemix side (goal 2).
 *
 * Transport (§8.1): a `bindService` from `com.oplus.melody` to BtRemix is refused by package visibility, so
 * the link is no longer *established* here. Instead the injected [MelodyDoorbellReceiver] receives a
 * package-restricted broadcast from BtRemix with the binder in its extras and calls [onDoorbell]; this class
 * then behaves exactly like a bound client (register listener, pull, push callbacks, re-attach on death).
 *
 * State machine (plan §6): `IDLE` (receiver registered, waiting for a doorbell) -> `BOUND` (proxy in hand,
 * listener registered) -> `DEAD` (death recipient or `DeadObjectException`) -> back to waiting. Outgoing
 * calls never block the host: they run on one worker thread with a hard timeout, so a wedged BtRemix
 * degrades to cached values plus a `melody.bridge.call_timeout` line instead of stalling the panel.
 */
internal class MelodyBridgeClient(
    private val context: Context,
    private val log: MelodyLog,
) {

    private val cache = MelodyBridgeCache()
    private val lock = Any()

    /** Single worker: one wedged call fails the queued ones fast instead of piling up binder transactions. */
    private val calls = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BtRemixMelodyCall").apply { isDaemon = true }
    }

    /**
     * Doorbell handling runs here rather than on [calls].
     *
     * Attaching does `register(listener)` and then immediately pulls `listManagedMacs()`. That pull is
     * itself submitted to [calls], so running the attach on [calls] would make it wait for the very worker
     * that is executing it - the first call after every connect then failed with `call_timeout` (measured on
     * device). Two single-threaded stages keep both properties: no pile-up, and no self-wait.
     */
    private val link = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BtRemixMelodyLink").apply { isDaemon = true }
    }

    private val receiver = MelodyDoorbellReceiver(log) { binder, generation, protocol, sender ->
        onDoorbell(binder, generation, protocol, sender)
    }

    private val deathRecipient = IBinder.DeathRecipient { onLinkLost("binder_died") }

    @Volatile
    private var service: IMelodyBridge? = null

    @Volatile
    private var generation: Int = 0

    @Volatile
    private var started = false

    /** Fresh whenever there is no live link; counted down by [onDoorbell]. */
    private var latch = CountDownLatch(1)

    private val listener = object : IMelodyBridgeListener.Stub() {
        override fun onSnapshot(mac: String?, snapshot: MelodySnapshot?) {
            if (snapshot == null) return
            val key = MelodyMac.normalize(mac ?: snapshot.mac)
            cache.recordSnapshot(key, snapshot.lifecycle, snapshot.stateKeys)
            log.event(
                "melody.bridge.push",
                "side" to SIDE,
                "mac" to key,
                "lifecycle" to snapshot.lifecycle,
                "keys" to snapshot.state.size(),
            )
        }

        override fun onSupportChanged() {
            log.event("melody.bridge.support_changed", "side" to SIDE)
            runCatching { managedMacs() }
        }
    }

    /** Registers the doorbell receiver. Safe to call repeatedly; the registration itself is idempotent. */
    fun start() {
        if (started) return
        started = true
        receiver.register(context)
        log.event("melody.bridge.client_started", "side" to SIDE)
    }

    fun stop() {
        started = false
        receiver.unregister()
        unlinkCurrent()
        synchronized(lock) {
            service = null
            latch = CountDownLatch(1)
        }
    }

    /** Waits up to [timeoutMs] for a doorbell; returns immediately when a link is already up. */
    fun awaitConnected(timeoutMs: Long): Boolean {
        if (service != null) return true
        start()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (service == null) {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) return false
            val gate = synchronized(lock) {
                // A zero count with no service means the link died; wait on a fresh gate.
                if (latch.count == 0L) latch = CountDownLatch(1)
                latch
            }
            runCatching { gate.await(remaining, TimeUnit.MILLISECONDS) }
        }
        return true
    }

    fun managedMacs(): List<String> {
        val macs = call("listManagedMacs") { bridge -> bridge.listManagedMacs().map(MelodyMac::normalize) }
        if (macs == null) return cache.managedMacs()
        cache.noteManagedMacs(macs)
        return macs
    }

    fun snapshot(mac: String): MelodySnapshot? {
        val key = MelodyMac.normalize(mac)
        val snapshot = call("snapshot") { bridge -> bridge.snapshot(key) }
        if (snapshot == null) {
            log.event(
                "melody.bridge.snapshot",
                "side" to SIDE,
                "mac" to key,
                "linked" to (service != null),
                "found" to false,
            )
            return null
        }
        cache.recordSnapshot(key, snapshot.lifecycle, snapshot.stateKeys)
        log.event(
            "melody.bridge.snapshot",
            "side" to SIDE,
            "mac" to key,
            "lifecycle" to snapshot.lifecycle,
            "keys" to snapshot.state.size(),
        )
        return snapshot
    }

    fun resolveSupport(mac: String): MelodySupportInfo? =
        call("resolveSupport") { bridge -> bridge.resolveSupport(MelodyMac.normalize(mac)) }

    fun execute(mac: String, actionId: String, args: Map<String, StateValue> = emptyMap()): Int {
        val key = MelodyMac.normalize(mac)
        val code = call("execute") { bridge ->
            bridge.execute(key, actionId, MelodyBundleCodec.encodeArgs(args))
        } ?: MelodyBridgeResult.ERROR_SESSION_UNAVAILABLE
        log.event(
            "melody.bridge.execute",
            "side" to SIDE,
            "mac" to key,
            "action" to actionId,
            "code" to code,
            "result" to MelodyBridgeResult.name(code),
        )
        return code
    }

    // --- connection internals -------------------------------------------------------------------

    /**
     * Takes a doorbell. Runs on the host's main thread (broadcast callback), so it only does bookkeeping
     * and hands the binder traffic to [calls].
     */
    private fun onDoorbell(binder: IBinder, incomingGeneration: Int, protocol: Int, sender: String?) {
        if (!MelodyDoorbellProtocol.accepts(incomingGeneration, generation, service != null)) {
            log.event(
                "melody.bridge.doorbell_duplicate",
                "side" to SIDE,
                "generation" to incomingGeneration,
            )
            return
        }
        val iface = runCatching { IMelodyBridge.Stub.asInterface(binder) }.getOrNull()
        if (iface == null) {
            log.warn("melody.bridge.doorbell_unusable")
            return
        }
        unlinkCurrent()
        val linkedToDeath = runCatching { binder.linkToDeath(deathRecipient, 0) }.isSuccess
        synchronized(lock) {
            service = iface
            generation = incomingGeneration
            latch.countDown()
        }
        log.event(
            "melody.bridge.connected",
            "side" to SIDE,
            "generation" to incomingGeneration,
            "protocol" to protocol,
            "sender" to sender,
            "link_to_death" to linkedToDeath,
        )
        // The panel is owed the current values, not only future changes; registering also tells BtRemix to
        // drop the doorbell burst and fall back to the keepalive.
        link.execute {
            runCatching { iface.register(listener) }
                .onFailure { log.warn("melody.bridge.register_failed", it) }
            runCatching { managedMacs() }
                .onFailure { log.warn("melody.bridge.list_failed", it) }
        }
    }

    private fun onLinkLost(reason: String) {
        synchronized(lock) {
            service = null
            latch = CountDownLatch(1)
        }
        log.event("melody.bridge.link_lost", "side" to SIDE, "reason" to reason)
    }

    /** Drops the previous proxy without touching the cache; a later doorbell replaces it. */
    private fun unlinkCurrent() {
        val previous = synchronized(lock) {
            val current = service
            service = null
            latch = CountDownLatch(1)
            current
        }
        if (previous == null) return
        runCatching { previous.asBinder().unlinkToDeath(deathRecipient, 0) }
        runCatching { previous.unregister(listener) }
    }

    /**
     * Runs one binder call with a hard timeout. `null` means "no live answer" - the caller decides whether
     * that is a cached value (list/snapshot) or an error code (execute), and the panel never sees a throw.
     */
    private fun <T> call(name: String, block: (IMelodyBridge) -> T): T? {
        val bridge = service ?: return null
        return try {
            calls.submit(Callable { block(bridge) }).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            log.event(
                "melody.bridge.call_timeout",
                "side" to SIDE,
                "call" to name,
                "timeout_ms" to CALL_TIMEOUT_MS,
            )
            null
        } catch (e: Exception) {
            log.warn("melody.bridge.call_failed", e)
            if (e.cause is DeadObjectException) onLinkLost("dead_object")
            null
        }
    }

    private companion object {
        const val SIDE = "melody"

        /** Plan §6: `execute`/`snapshot` answer within ~2s or degrade; the panel must never block on us. */
        const val CALL_TIMEOUT_MS = 2_000L
    }
}

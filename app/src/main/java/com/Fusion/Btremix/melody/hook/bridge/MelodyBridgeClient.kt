package com.Fusion.Btremix.melody.hook.bridge

import android.content.Context
import android.os.DeadObjectException
import android.os.IBinder
import android.os.SystemClock
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.melody.api.MelodyBridgeCache
import com.Fusion.Btremix.melody.api.MelodyBridgeResult
import com.Fusion.Btremix.melody.api.MelodyBundleCodec
import com.Fusion.Btremix.melody.api.MelodyDeviceInfoIdentity
import com.Fusion.Btremix.melody.api.MelodyDeviceInfoProjection
import com.Fusion.Btremix.melody.api.MelodyDevicePolicy
import com.Fusion.Btremix.melody.api.MelodyDoorbellProtocol
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.api.MelodyPanelPolicy
import com.Fusion.Btremix.melody.api.MelodyProjectionStore
import com.Fusion.Btremix.melody.api.MelodyProviderMerge
import com.Fusion.Btremix.melody.api.MelodySnapshot
import com.Fusion.Btremix.melody.api.MelodySupportInfo
import com.Fusion.Btremix.melody.api.MelodyWhitelistIdentity
import com.Fusion.Btremix.melody.api.WireValue
import com.Fusion.Btremix.melody.bridge.IMelodyBridge
import com.Fusion.Btremix.melody.bridge.IMelodyBridgeListener
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.projection.MelodyProjectionBuilder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Cold-start cache (MELODY_BRIDGE_SPEC §5.5, M3.3): the managed set plus every projection envelope,
     * persisted in the host's own `SharedPreferences` so the provider can answer while BtRemix is not
     * running. The in-memory mirror inside [MelodyProjectionStore] is what the provider path reads.
     */
    private val store = MelodyProjectionStore()
    private val storePreferences = MelodyProjectionPreferences(context, store)

    /**
     * Identity half of the M3.4 `DeviceInfo` projection, keyed by MAC. The host polls
     * `DeviceInfoManager` for every bonded device, so the envelope must not be re-parsed per lookup;
     * entries are dropped whenever a fresh envelope or a new managed set arrives.
     */
    private val identities = ConcurrentHashMap<String, MelodyWhitelistIdentity>()

    /** The envelope's own `definition` policy, parsed alongside the identity and invalidated with it. */
    private val policies = ConcurrentHashMap<String, MelodyDevicePolicy>()

    /**
     * The envelope's `panel` policy per MAC (M4.2). Parsed once per envelope - the panel rebuilds its
     * list several times per page, so re-reading the JSON on every refresh would be wasted work.
     */
    private val panels = ConcurrentHashMap<String, MelodyPanelPolicy>()

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

    init {
        // A persisted document from another envelope version is not "empty": report it once, then use
        // whatever this process learns from the live bridge.
        if (!storePreferences.load() && storePreferences.hasPersisted()) {
            log.event(
                "melody.projection.version_mismatch",
                "side" to SIDE,
                "expected" to MelodyProjectionBuilder.ENVELOPE_VERSION,
            )
        }
    }

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
            // The Definition set (or the device pairing) changed: drop the M3.4 identity cache so the
            // next DeviceInfo lookup rebuilds it from the refreshed envelope.
            identities.clear()
            policies.clear()
            panels.clear()
            // Refresh on the link stage: this callback runs on a binder thread, and the refresh pulls
            // one `managedMacs` plus one `resolveProjection` per device.
            link.execute { runCatching { refreshFromBridge() }.onFailure { log.warn("melody.bridge.list_failed", it) } }
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
        // A live answer refreshes both the M2b in-memory cache and the M3.3 persisted cold-start store;
        // without a link the persisted set is the better answer (it survives BtRemix being killed).
        if (macs == null) return store.managedMacs().ifEmpty { cache.managedMacs() }
        cache.noteManagedMacs(macs)
        store.noteManagedMacs(macs, MelodyProjectionBuilder.ENVELOPE_VERSION)
        storePreferences.save()
        return macs
    }

    /**
     * Provider-path managed set: the persisted list answers immediately, and only a cold cache pays for
     * the short IPC budget (the doorbell prefetch keeps it warm while BtRemix is up).
     */
    fun managedMacsFast(): List<String> {
        store.managedMacs().takeIf { it.isNotEmpty() }?.let { return it }
        val macs = call("listManagedMacs", PROVIDER_CALL_TIMEOUT_MS) { bridge ->
            bridge.listManagedMacs().map(MelodyMac::normalize)
        } ?: return emptyList()
        if (macs.isEmpty()) return macs
        cache.noteManagedMacs(macs)
        store.noteManagedMacs(macs, MelodyProjectionBuilder.ENVELOPE_VERSION)
        storePreferences.save()
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

    /**
     * Pulls the synthesised whitelist envelope for [mac] (M3) and persists it. `null` means "no live
     * answer"; the caller decides whether that is a cached value (the provider) or an error (the panel).
     */
    fun projection(mac: String): String? {
        val key = MelodyMac.normalize(mac)
        val json = call("resolveProjection") { bridge -> bridge.resolveProjection(key) } ?: return null
        store.put(key, json, MelodyProjectionBuilder.ENVELOPE_VERSION)
        storePreferences.save()
        identities.remove(key)
        policies.remove(key)
        panels.remove(key)
        return json
    }

    /**
     * Provider-path projection (M3.3 plan "冷启动缓存"): the persisted envelope answers immediately;
     * only a cache miss pays for a short IPC, and a miss after that returns `null` so the caller can
     * hand the host's own answer back (recorded as a stale projection).
     */
    fun projectionFast(mac: String): String? {
        val key = MelodyMac.normalize(mac)
        store.envelope(key)?.let { cached ->
            if (store.isStale(PROVIDER_STALE_AFTER_MS)) {
                log.event(
                    "melody.projection.stale",
                    "side" to SIDE,
                    "mac" to key,
                    "reason" to "cache",
                    "age_ms" to store.ageMs(),
                )
            }
            return cached
        }
        val json = call("resolveProjection", PROVIDER_CALL_TIMEOUT_MS) { bridge -> bridge.resolveProjection(key) }
        if (json == null) {
            log.event("melody.projection.stale", "side" to SIDE, "mac" to key, "reason" to "miss")
            return null
        }
        store.put(key, json, MelodyProjectionBuilder.ENVELOPE_VERSION)
        storePreferences.save()
        return json
    }

    /**
     * Live "both in ear" flag for [mac], or `null` when there is no session or the definition declares
     * no wear state. Wear is inherently live, so it is never cached: no link means no wear row.
     */
    fun wearState(mac: String): Boolean? {
        val key = MelodyMac.normalize(mac)
        val snapshot = call("snapshot", PROVIDER_CALL_TIMEOUT_MS) { bridge -> bridge.snapshot(key) } ?: return null
        return wearOf(snapshot)
    }

    /**
     * Cached session lifecycle for [mac]; no IPC. The panel pushes keep this warm while a session
     * exists, and a paired-but-not-connected device correctly reads as `null` (=> disconnected).
     */
    fun lifecycleFast(mac: String): String? = cache.snapshot(MelodyMac.normalize(mac))?.lifecycle

    /**
     * The M3.4 `DeviceInfo` projection for [mac] (identity + connection state), or `null` when there is
     * neither a live nor a cached envelope. Cheap after the first call: the envelope is parsed once and
     * only the lifecycle is re-read.
     */
    fun deviceInfoFast(mac: String): MelodyDeviceInfoProjection? {
        val key = MelodyMac.normalize(mac)
        val identity = whitelistIdentityFast(key) ?: return null
        val info = MelodyDeviceInfoIdentity.of(identity, policyOf(key)) ?: return null
        return MelodyDeviceInfoProjection.from(info, lifecycleFast(key))
    }

    /**
     * Whether the Definition asked for the transport backstops (`suppressMelodyTransport`).
     *
     * The caller only asks about MACs in the managed set, so an unreadable envelope still answers
     * `true`: for a device we claim to support, keeping Melody off its RFCOMM channel is the safe
     * default (M3-D7).
     */
    fun transportSuppressedFast(mac: String): Boolean =
        whitelistIdentityFast(mac)?.let { policyOf(mac).suppressTransport } ?: true

    /**
     * The raw whitelist identity of [mac] from the (cached) projection envelope. M3.4's in-memory
     * whitelist-repository hook needs both encodings of the product id and the whitelist JSON itself,
     * so this exposes the parsed identity rather than the DeviceInfo-shaped one.
     */
    fun whitelistIdentityFast(mac: String): MelodyWhitelistIdentity? {
        val key = MelodyMac.normalize(mac)
        identities[key]?.let { return it }
        val envelope = projectionFast(key) ?: return null
        val identity = MelodyProviderMerge.identityOf(envelope) ?: return null
        identities[key] = identity
        policies[key] = MelodyProviderMerge.policyOf(envelope)
        return identity
    }

    private fun policyOf(mac: String): MelodyDevicePolicy =
        policies[MelodyMac.normalize(mac)] ?: MelodyDevicePolicy.NEUTRAL

    /**
     * The M4.2 panel policy for [mac] from the (cached) projection envelope, or `null` when there is
     * neither a live nor a cached envelope. Cheap after the first call: the envelope is parsed once and
     * a degraded policy is cached just like a usable one, so a broken envelope cannot turn into a
     * re-parse per refresh.
     */
    fun panelFast(mac: String): MelodyPanelPolicy? {
        val key = MelodyMac.normalize(mac)
        panels[key]?.let { return it }
        val envelope = projectionFast(key) ?: return null
        val fallbackTitle = whitelistIdentityFast(key)?.name ?: DEFAULT_PANEL_TITLE
        val policy = MelodyProviderMerge.panelOf(envelope, fallbackTitle)
        panels[key] = policy
        return policy
    }

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
            runCatching { refreshFromBridge() }
                .onFailure { log.warn("melody.bridge.list_failed", it) }
        }
    }

    /**
     * Pulls the managed set and then the projection envelope of every managed device, so the provider
     * path (`projectionFast`) is a pure cache read in steady state (M3.3 plan "冷启动缓存").
     */
    private fun refreshFromBridge() {
        managedMacs().forEach { mac ->
            runCatching { projection(mac) }.onFailure { log.warn("melody.bridge.projection_failed", it) }
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
    private fun <T> call(name: String, timeoutMs: Long = CALL_TIMEOUT_MS, block: (IMelodyBridge) -> T): T? {
        val bridge = service ?: return null
        return try {
            calls.submit(Callable { block(bridge) }).get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            log.event(
                "melody.bridge.call_timeout",
                "side" to SIDE,
                "call" to name,
                "timeout_ms" to timeoutMs,
            )
            null
        } catch (e: Exception) {
            log.warn("melody.bridge.call_failed", e)
            if (e.cause is DeadObjectException) onLinkLost("dead_object")
            null
        }
    }

    /** `bothInEar` / `inEar` boolean from a session snapshot; `null` when the device has no such state. */
    private fun wearOf(snapshot: MelodySnapshot): Boolean? {
        val key = snapshot.state.keySet()
            .firstOrNull { name -> WEAR_KEYS.any { it.equals(name, ignoreCase = true) } }
            ?: return null
        return when (val value = MelodyBundleCodec.decode(snapshot.state.getBundle(key))) {
            is WireValue.Bool -> value.value
            is WireValue.Int32 -> value.value != 0
            is WireValue.Int64 -> value.value != 0L
            is WireValue.Text -> value.value.equals("true", ignoreCase = true)
            else -> null
        }
    }

    private companion object {
        const val SIDE = "melody"

        /** Plan §6: `execute`/`snapshot` answer within ~2s or degrade; the panel must never block on us. */
        const val CALL_TIMEOUT_MS = 2_000L

        /**
         * Provider path budget (Spec §5.5 "短超时 IPC（< 50 ms）"): the provider runs on a host thread the
         * system UI may be waiting on, and a warm cache means this is only paid on the very first ask.
         */
        const val PROVIDER_CALL_TIMEOUT_MS = 50L

        /** Serve the persisted envelope regardless of age, but tell the truth when it is older than this. */
        const val PROVIDER_STALE_AFTER_MS = 10 * 60 * 1000L

        /** Session state keys a Definition may use for "both earbuds are in"; none exist for XM3 (M3). */
        val WEAR_KEYS = listOf("bothInEar", "inEar")

        /** Only reachable when a (malformed) envelope omitted `sectionTitle`; M4.3 uses it as the group title. */
        const val DEFAULT_PANEL_TITLE = "BtRemix"
    }
}

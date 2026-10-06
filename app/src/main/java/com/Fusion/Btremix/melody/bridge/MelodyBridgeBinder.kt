package com.Fusion.Btremix.melody.bridge

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.RemoteCallbackList
import android.os.SystemClock
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.ProtocolSession
import com.Fusion.Btremix.device.session.SessionRegistry
import com.Fusion.Btremix.melody.api.MelodyBridgeResult
import com.Fusion.Btremix.melody.api.MelodyBundleCodec
import com.Fusion.Btremix.melody.api.MelodyCallPolicy
import com.Fusion.Btremix.melody.api.MelodyDiagnosticPolicy
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.api.MelodySnapshot
import com.Fusion.Btremix.melody.api.MelodySupportInfo
import com.Fusion.Btremix.melody.config.MelodySupportRegistry
import com.Fusion.Btremix.melody.projection.MelodyProjectionBuilder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * BtRemix side of the Melody bridge (MELODY_BRIDGE_SPEC §6.4, §8, §12 M2b).
 *
 * Every call re-checks `Binder.getCallingUid()` and resolves the packages owned by that UID before
 * doing anything: the service is exported because `com.oplus.melody` cannot request a BtRemix-owned
 * permission, so the UID is the only trustworthy identity (MELODY_BRIDGE_SPEC §8).
 *
 * The binder never opens a connection. Sessions are owned by the process-scoped [SessionRegistry] and
 * this class only observes them: [snapshot] reads the current value, [execute] forwards to the very same
 * [ProtocolSession] the Compose page is already using (D6, single control channel), and the registered
 * listeners receive pushed snapshots whenever a session's lifecycle or state changes.
 *
 * NOTE: how the host *obtains* this stub changed in M2b (§8.1: the host cannot see BtRemix, and BtRemix
 * cannot hold the host's signature permissions). It now arrives through the doorbell broadcast
 * (MELODY_BRIDGE_TRANSPORT_PLAN.md §3-§4) instead of `bindService`; the contract itself - method set, UID
 * check and session semantics - is transport-independent and unchanged.
 */
internal class MelodyBridgeBinder(
    private val context: Context,
    private val sessions: SessionRegistry,
    private val registry: MelodySupportRegistry,
    private val projection: MelodyProjectionBuilder,
    /**
     * Fixed `assets/icon.png` reader, keyed by **Definition id**. The host asks for it by MAC when it is
     * about to draw the device's placeholder picture on the detail / OneSpace header.
     */
    private val iconBytes: (String) -> ByteArray? = { null },
    private val log: MelodyBridgeLog = MelodyBridgeLog(),
    private val scope: CoroutineScope,
    private val selfUid: Int = Process.myUid(),
    /** M5.4 D-31: re-sends one doorbell broadcast for the other host process. */
    private val onDoorbellRequested: (String) -> Unit = {},
) : IMelodyBridge.Stub() {

    private val listeners = RemoteCallbackList<IMelodyBridgeListener>()
    private val watchers = ConcurrentHashMap<String, Job>()

    /**
     * `RemoteCallbackList` refuses *overlapping* broadcasts ("beginBroadcast() called while already in a
     * broadcast") - it is single-threaded by design, while M2b pushes from several places at once: the
     * watcher coroutines of every managed MAC, `onSupportChanged`, and each `register`. With two host
     * processes attaching simultaneously the loser's push aborted and the exception travelled back to the
     * panel as a failed `register` (measured on device). Serialising the begin/end pair is the fix; the
     * broadcast itself is short because a listener callback is a one-way transport for the panel.
     */
    private val broadcastLock = Any()

    /**
     * UID of `com.oplus.melody` resolved once at construction. The package list from
     * `getPackagesForUid` is the primary check; this is the belt-and-braces fallback in case package
     * visibility filters that list on some OEM build.
     */
    private val hostUid: Int? = runCatching {
        context.packageManager.getPackageInfo(MelodyCallPolicy.HOST_PACKAGE, 0).applicationInfo?.uid
    }.getOrNull()

    /** Starts as soon as the binder is created so the push list tracks sessions without polling. */
    private val watchJob: Job = scope.launch {
        sessions.managedMacsFlow.collect { macs -> syncWatchers(macs) }
    }

    /**
     * The managed set (paired device with a `melody` Definition) is independent of live sessions, and
     * the panel has to re-pull `resolveProjection` when it changes. `onSupportChanged` is the invalidation
     * signal, so it is broadcast on registry changes as well (M3-D6). `definitionChanges` also fires when
     * an already-managed MAC gets a new Definition version (M4.2), which is what makes re-installing a
     * dcpkg with a new `melody.panel` policy reach the host without restarting either process.
     */
    private val definitionJob: Job = scope.launch {
        registry.definitionChanges.collect { broadcastSupportChanged() }
    }

    @Volatile
    private var closed = false

    /** M5.4 D-31: last `requestDoorbell` accepted, so a looping caller cannot spam broadcasts. */
    private var lastDoorbellRequestMs = 0L

    override fun listManagedMacs(): MutableList<String> {
        if (!authorize("listManagedMacs")) return ArrayList()
        val macs = managedMacs()
        log.event(
            "melody.bridge.list",
            "caller_uid" to Binder.getCallingUid(),
            "sessions" to sessions.managedMacs().size,
            "count" to macs.size,
        )
        return ArrayList(macs)
    }

    override fun resolveSupport(mac: String?): MelodySupportInfo? {
        if (!authorize("resolveSupport")) return null
        val key = mac?.let(MelodyMac::normalize) ?: return null
        val device = registry.support(key)
        val managed = device != null || sessions.find(key) != null
        log.event(
            "melody.bridge.support",
            "mac" to key,
            "managed" to managed,
            "definition" to device?.packageId,
            "stage" to "m3",
        )
        val support = device?.melody?.support ?: return MelodySupportInfo.managedOnly(key, managed)
        return MelodySupportInfo(
            mac = key,
            managed = true,
            name = support.name,
            brand = support.brand ?: support.name,
            productId = support.productId,
            productType = support.productType,
            uuid = support.uuid,
            supportSpp = support.supportSpp,
        )
    }

    override fun resolveProjection(mac: String?): String? {
        if (!authorize("resolveProjection")) return null
        val key = mac?.let(MelodyMac::normalize) ?: return null
        val device = registry.support(key)
        if (device == null) {
            log.event("melody.projection.miss", "mac" to key, "stage" to "m3")
            return null
        }
        val result = runCatching { projection.project(device) }.getOrElse {
            log.warn("melody.projection.failed", it)
            return null
        }
        // The declared template was unusable; `templateUse` says whether the built-in fallback saved
        // the full field set or the projection had to degrade to the minimal one (M3.2, Spec §5.4.4).
        if (result.templateMissing) {
            log.event(
                "melody.whitelist.template_missing",
                "mac" to key,
                "definition" to device.packageId,
                "template" to result.templateUse.name.lowercase(),
            )
        }
        log.event(
            "melody.projection.built",
            "mac" to key,
            "definition" to device.packageId,
            "version" to MelodyProjectionBuilder.ENVELOPE_VERSION,
            "template" to result.templateUse.name.lowercase(),
            "bytes" to result.json.toByteArray(Charsets.UTF_8).size,
        )
        return result.json
    }

    override fun snapshot(mac: String?): MelodySnapshot? {
        if (!authorize("snapshot")) return null
        val key = mac?.let(MelodyMac::normalize) ?: return null
        val payload = payloadFor(key)
        log.event(
            "melody.bridge.snapshot",
            "mac" to key,
            "lifecycle" to payload.lifecycle,
            "keys" to payload.state.size(),
        )
        return payload
    }

    /**
     * M7 header artwork: the managed Definition's own `assets/icon.png`.
     *
     * Only the Definition id is resolved here - the bytes are the very same file the Devices page shows
     * (D-UI-5), so the two front-ends cannot disagree about the picture either. A MAC that is not
     * managed by a `melody` Definition answers `null`, which the host turns into "keep the placeholder".
     */
    override fun resolveIcon(mac: String?): ByteArray? {
        if (!authorize("resolveIcon")) return null
        val key = mac?.let(MelodyMac::normalize) ?: return null
        val packageId = registry.support(key)?.packageId
        val bytes = packageId?.let { id -> runCatching { iconBytes(id) }.getOrNull() }
        log.event(
            "melody.bridge.icon",
            "mac" to key,
            "definition" to packageId,
            "bytes" to (bytes?.size ?: 0),
        )
        return bytes?.takeIf { it.isNotEmpty() }
    }

    override fun execute(mac: String?, actionId: String?, args: Bundle?): Int {
        if (!authorize("execute")) return MelodyBridgeResult.ERROR_UNAUTHORIZED
        val key = mac?.let(MelodyMac::normalize)
        if (key.isNullOrBlank() || actionId.isNullOrBlank()) {
            log.warn("melody.bridge.execute.invalid")
            return MelodyBridgeResult.ERROR_INVALID_ARGUMENT
        }
        val session = sessions.find(key)
        if (session == null) {
            log.event("melody.bridge.execute.unavailable", "mac" to key, "action" to actionId)
            return MelodyBridgeResult.ERROR_SESSION_UNAVAILABLE
        }
        val action = runCatching { DeviceAction(actionId, MelodyBundleCodec.decodeArgs(args)) }
            .getOrElse {
                log.warn("melody.bridge.execute.bad_args", it)
                return MelodyBridgeResult.ERROR_INVALID_ARGUMENT
            }
        val code = runCatching {
            when (val result = runBlocking { session.execute(action) }) {
                is ActionResult.Success -> MelodyBridgeResult.OK
                is ActionResult.Failure -> MelodyBridgeResult.from(result.error)
            }
        }.getOrElse {
            log.warn("melody.bridge.execute.failed", it)
            MelodyBridgeResult.ERROR_INTERNAL
        }
        log.event(
            "melody.bridge.execute",
            "mac" to key,
            "action" to actionId,
            "code" to code,
            "result" to MelodyBridgeResult.name(code),
        )
        return code
    }

    override fun register(listener: IMelodyBridgeListener?) {
        if (!authorize("register")) return
        if (listener == null) return
        listeners.register(listener)
        log.event("melody.bridge.register", "caller_uid" to Binder.getCallingUid(), "listeners" to listeners.registeredCallbackCount)
        // A panel that has just attached is owed the current values, not only future changes. These go
        // straight to the new listener: the other host process already has them, and a broadcast here would
        // be the concurrent one the callback list rejects.
        sessions.managedMacs().forEach { mac -> pushTo(listener, mac) }
    }

    override fun unregister(listener: IMelodyBridgeListener?) {
        if (listener == null) return
        listeners.unregister(listener)
        log.event("melody.bridge.unregister", "listeners" to listeners.registeredCallbackCount)
    }

    /**
     * M5.4 D-28: one structured diagnostic line from the host process. `oneway`, so this only ever
     * appends to the ring buffer; the whitelist keeps a mis-scoped client from filling it.
     */
    override fun reportDiagnostics(name: String?, fields: Bundle?) {
        if (!authorize("reportDiagnostics")) return
        val event = name ?: return
        if (!MelodyDiagnosticPolicy.forwardsToBridge(event)) return
        val pairs = ArrayList<Pair<String, Any?>>(fields?.size() ?: 0)
        fields?.keySet()?.forEach { key -> pairs += key to fields.getString(key) }
        MelodyDiagnosticStore.record(event, pairs)
    }

    /**
     * M5.4 D-31: a host process that just attached asks for one extra doorbell, so the sibling `:fg`
     * process does not wait up to 30 s for the keepalive. Rate-limited to once a second in case a
     * mis-scoped client calls in a loop.
     */
    override fun requestDoorbell() {
        if (!authorize("requestDoorbell")) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDoorbellRequestMs < DOORBELL_REQUEST_MIN_INTERVAL_MS) return
        lastDoorbellRequestMs = now
        log.event("melody.bridge.doorbell_request", "caller_uid" to Binder.getCallingUid())
        runCatching { onDoorbellRequested("host_attached") }
            .onFailure { log.warn("melody.bridge.doorbell_request_failed", it) }
    }

    /**
     * True while at least one host process holds a listener (MELODY_BRIDGE_TRANSPORT_PLAN.md §4.3).
     *
     * The doorbell sender reads this to choose its cadence: a burst while nobody is attached, then the
     * keepalive. It is intentionally a plain read instead of a callback so the sender stays independent of
     * the listener lifecycle.
     */
    fun hasClients(): Boolean = !closed && listeners.registeredCallbackCount > 0

    /** Cancels every watcher and drops the listener registrations; called when the service dies. */
    fun close() {
        if (closed) return
        closed = true
        watchJob.cancel()
        definitionJob.cancel()
        watchers.values.forEach(Job::cancel)
        watchers.clear()
        listeners.kill()
        scope.cancel()
    }

    // --- internals ------------------------------------------------------------------------------

    private fun authorize(method: String): Boolean {
        if (closed) return false
        val uid = Binder.getCallingUid()
        val packages = runCatching {
            context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        }.getOrElse {
            log.warn("melody.bridge.packages_failed", it)
            emptyList()
        }
        val allowed = MelodyCallPolicy.isAuthorized(packages, uid, selfUid, hostUid)
        if (!allowed) {
            log.event(
                "melody.bridge.rejected",
                "method" to method,
                "uid" to uid,
                "packages" to packages.joinToString(","),
            )
        }
        return allowed
    }

    private fun payloadFor(mac: String): MelodySnapshot {
        val snapshot = sessions.snapshot(mac)
        return snapshot?.let(MelodyBundleCodec::encodeSnapshot) ?: MelodySnapshot.disconnected(mac)
    }

    /**
     * The bridge's managed set: live sessions *and* paired devices claimed by a `melody` Definition.
     * The union keeps M2b's "a session is always listed" behaviour while adding the support-injection
     * case (paired, not connected).
     */
    private fun managedMacs(): List<String> =
        (sessions.managedMacs() + registry.managedMacs()).distinct().sorted()

    private fun syncWatchers(macs: List<String>) {
        if (closed) return
        val live = macs.toSet()
        watchers.keys.filterNot { it in live }.forEach { mac -> watchers.remove(mac)?.cancel() }
        var attached = false
        macs.forEach { mac ->
            if (watchers.containsKey(mac)) return@forEach
            val session = sessions.find(mac) ?: return@forEach
            watchers[mac] = scope.launch { watch(mac, session) }
            attached = true
        }
        if (attached) broadcastSupportChanged()
    }

    private suspend fun watch(mac: String, session: ProtocolSession) = coroutineScope {
        launch { session.lifecycle.collect { push(mac) } }
        launch { session.state.entries.collect { push(mac) } }
    }

    private fun push(mac: String) {
        if (closed || listeners.registeredCallbackCount == 0) return
        val payload = payloadFor(mac)
        broadcast { listener -> listener.onSnapshot(mac, payload) }
    }

    /** Direct delivery for the caller that is already known (see [register]). */
    private fun pushTo(listener: IMelodyBridgeListener, mac: String) {
        if (closed) return
        val payload = payloadFor(mac)
        runCatching { listener.onSnapshot(mac, payload) }
            .onFailure { log.warn("melody.bridge.push_failed", it) }
    }

    private fun broadcastSupportChanged() {
        if (closed || listeners.registeredCallbackCount == 0) return
        broadcast(IMelodyBridgeListener::onSupportChanged)
    }

    private inline fun broadcast(action: (IMelodyBridgeListener) -> Unit) {
        synchronized(broadcastLock) {
            val count = listeners.beginBroadcast()
            try {
                for (index in 0 until count) {
                    val listener = listeners.getBroadcastItem(index)
                    runCatching { action(listener) }.onFailure { log.warn("melody.bridge.push_failed", it) }
                }
            } finally {
                listeners.finishBroadcast()
            }
        }
    }

    private companion object {
        const val DOORBELL_REQUEST_MIN_INTERVAL_MS = 1_000L
    }
}

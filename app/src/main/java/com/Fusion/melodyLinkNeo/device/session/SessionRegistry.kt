package com.fusion.melodyLinkNeo.device.session

import com.fusion.melodyLinkNeo.device.runtime.ProtocolSession
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped owner of live device sessions, keyed by normalised MAC address.
 *
 * MELODY_BRIDGE_SPEC §3.3 makes single-session ownership the most important constraint: the same
 * physical headset may be driven from exactly one place at a time. The registry is that place: the
 * Compose UI and the Melody bridge both [acquire] the same instance instead of opening their own
 * connection (§12 M2a/§12 D6).
 *
 * Ownership is reference counted. [acquire] lazily opens a session through the caller's `open`
 * lambda only when the MAC is unknown, and concurrent acquires for the same MAC share one creation;
 * [release] decrements the count and closes the session only when the last holder lets go. A
 * front-end must never close a session it does not exclusively own - "disconnect the device" is a
 * device action plus releasing one's own reference, not tearing down someone else's session.
 *
 * Lookups are MAC-keyed and case/whitespace insensitive: Android hands out addresses in several
 * shapes across `BluetoothDevice`, `BleScanResult` and provider extras, and a mismatch would
 * silently allow a second session.
 */
class SessionRegistry(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val entries = ConcurrentHashMap<String, Entry>()

    /** One registry slot: the session plus how many front-ends currently hold it. */
    private class Entry(val session: ProtocolSession, var refCount: Int)

    private val managedFlow = MutableStateFlow<List<String>>(emptyList())

    /**
     * The normalised MACs with a live session, as an observable list (MELODY_BRIDGE_SPEC §12 M2b).
     *
     * The Melody bridge has to start and stop one state watcher per managed device as sessions come and
     * go, and polling [managedMacs] would either miss short-lived sessions or burn a timer. This flow is
     * updated on every ownership change (acquire/release/register/remove/close/closeAll) so the bridge
     * can react exactly once per transition.
     */
    val managedMacsFlow: StateFlow<List<String>> = managedFlow.asStateFlow()

    /** Per-MAC locks so an `open` for one device never serialises work for another device. */
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(key: String): Mutex = keyLocks.computeIfAbsent(key) { Mutex() }

    /** Republication of the key set; call after any mutation of [entries]. */
    private fun refreshManaged() {
        managedFlow.value = entries.keys.sorted()
    }

    /** The session currently owning [mac], or `null` when the MAC is not managed. */
    fun find(mac: String): ProtocolSession? = entries[normalize(mac)]?.session

    /** Normalised MACs that currently have a registered session, in a stable order. */
    fun managedMacs(): List<String> = entries.keys.sorted()

    /** How many front-ends currently hold the session for [mac]; `0` when the MAC is unknown. */
    fun refCount(mac: String): Int = entries[normalize(mac)]?.refCount ?: 0

    /**
     * Acquires a reference to the session for [mac], opening one with [open] the first time.
     *
     * [open] runs at most once per MAC for concurrent callers: everyone else either receives the
     * already-registered session or waits for the in-flight creation to finish. When [open] throws,
     * nothing is registered and the caller sees the failure.
     */
    suspend fun acquire(mac: String, open: suspend () -> ProtocolSession): ProtocolSession {
        val key = normalize(mac)
        return lockFor(key).withLock {
            entries[key]?.let { entry ->
                entry.refCount += 1
                return@withLock entry.session
            }
            val session = open()
            entries[key] = Entry(session, 1)
            refreshManaged()
            session
        }
    }

    /**
     * Releases one reference to the session for [mac].
     *
     * The session is closed (and the slot freed) only when the last holder releases it. Unknown MACs
     * are a no-op so a race between two front-ends disconnecting cannot throw. Closing happens while
     * the per-MAC lock is held, so a concurrent [acquire] for the same MAC can never see a
     * half-closed session or end up with a second live one.
     */
    suspend fun release(mac: String) {
        val key = normalize(mac)
        lockFor(key).withLock {
            val entry = entries[key] ?: return@withLock
            entry.refCount -= 1
            if (entry.refCount > 0) return@withLock
            entries.remove(key)
            refreshManaged()
            entry.session.close()
        }
    }

    /** Fire-and-forget [release] for callers without a live coroutine (e.g. ViewModel teardown). */
    fun releaseAsync(mac: String) {
        scope.launch { release(mac) }
    }

    /** A detached copy of the current lifecycle and state for [mac], or `null` when unmanaged. */
    fun snapshot(mac: String): SessionSnapshot? {
        val key = normalize(mac)
        val entry = entries[key] ?: return null
        return snapshotOf(key, entry.session)
    }

    /** A detached copy of every managed session, ordered by normalised MAC. */
    fun snapshots(): List<SessionSnapshot> = managedMacs().mapNotNull { mac ->
        entries[mac]?.let { snapshotOf(mac, it.session) }
    }

    /**
     * Low-level registration primitive used by tests and bootstrap code.
     *
     * Production front-ends must go through [acquire] so the reference count stays correct; this
     * seeds a slot with a single reference and returns the previous owner when one was replaced.
     */
    fun register(mac: String, session: ProtocolSession): ProtocolSession? =
        entries.put(normalize(mac), Entry(session, 1))?.session.also { refreshManaged() }

    /** Detaches the session for [mac] without closing it. */
    fun remove(mac: String): ProtocolSession? =
        entries.remove(normalize(mac))?.session.also { if (it != null) refreshManaged() }

    /** Detaches and closes the session for [mac]. Safe when the MAC is unknown. */
    suspend fun close(mac: String) {
        remove(mac)?.close()
    }

    /** Detaches and closes every registered session; used when the owning service shuts down. */
    suspend fun closeAll() {
        while (true) {
            val mac = entries.keys.firstOrNull() ?: break
            entries.remove(mac)?.session?.close()
        }
        refreshManaged()
    }

    companion object {
        /** Canonical form used for every registry key: trimmed and upper-cased with a fixed locale. */
        fun normalize(mac: String): String = mac.trim().uppercase(Locale.ROOT)
    }
}

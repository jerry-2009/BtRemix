package com.fusion.melodyLinkNeo.device.session

import com.fusion.melodyLinkNeo.device.runtime.ProtocolSession
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One live session with the moment the UI started holding it. */
data class ManagedSession(
    val mac: String,
    val snapshot: SessionSnapshot,
    val heldSince: Instant?,
)

/**
 * Why an app-scoped hold exists (HANDOFF_AUTO_SESSION.md §6 step 2).
 *
 * Holds are released per reason so turning one feature off can never tear down a session that a
 * different feature is still keeping alive: switching "后台运行" off must not kill a session the
 * "蓝牙连接时自动建立会话" switch owns, and vice versa.
 */
enum class HoldReason {
    /** D-UI-3 exception: the session outlives the device session screen. */
    BACKGROUND_RUN,

    /** Event-driven session kept alive while the classic link is up (HANDOFF_AUTO_SESSION.md). */
    AUTO_CONNECT,
}

/**
 * UI-facing wrapper around [SessionRegistry] (DEVICE_CENTER_UI_PLAN §3.2/D-UI-3).
 *
 * The registry owns reference counting; this class only adds presentation state: a polled snapshot
 * list (the registry itself has no observable session bodies) and the "会话时间" anchor, which is the
 * moment the *UI* acquired the session - not when the device connected, since the Melody bridge may
 * have opened it earlier.
 */
class SessionManager(
    private val registry: SessionRegistry,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val pollIntervalMs: Long = 1_000L,
) {
    private val heldSince = ConcurrentHashMap<String, Instant>()

    /**
     * Normalised MAC → the reasons currently owning an app-scoped reference.
     *
     * Values are replaced as a whole under [ConcurrentHashMap.compute]/[computeIfPresent] so a
     * concurrent hold/release for the same MAC can never observe a half-applied reason set.
     */
    private val holds = ConcurrentHashMap<String, Set<HoldReason>>()

    private val mutableSessions = MutableStateFlow<List<ManagedSession>>(emptyList())
    val sessions: StateFlow<List<ManagedSession>> = mutableSessions.asStateFlow()

    /** Detached snapshots for consumers that only care about lifecycle/state (e.g. DeviceRegistry). */
    val snapshots: StateFlow<List<SessionSnapshot>> = sessions
        .map { list -> list.map { it.snapshot } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activeSessionCount: StateFlow<Int> = sessions
        .map { list -> list.count { it.snapshot.lifecycle.isLive() } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        scope.launch {
            while (isActive) {
                refresh()
                delay(pollIntervalMs)
            }
        }
    }

    /** Records "the UI started holding [mac]" so the session timer has a stable origin. */
    fun noteHeld(mac: String) {
        heldSince.putIfAbsent(SessionRegistry.normalize(mac), clock.instant())
        refresh()
    }

    /** Drops the timer origin once the UI releases the session. */
    fun noteReleased(mac: String) {
        heldSince.remove(SessionRegistry.normalize(mac))
        refresh()
    }

    fun setBackgroundRun(mac: String, enabled: Boolean) {
        // Only affects whether the UI releases on exit; the timer anchor is kept either way while
        // the session is still alive so returning to the page does not restart the clock.
        if (!enabled) refresh()
    }

    /** Normalised MACs that currently hold an app-scoped reference for [reason]. */
    fun heldMacs(reason: HoldReason): Set<String> =
        holds.entries.asSequence()
            .filter { reason in it.value }
            .mapTo(linkedSetOf()) { it.key }

    /** True when [mac] already holds an app-scoped reference for [reason]. */
    fun isHeld(mac: String, reason: HoldReason): Boolean =
        reason in (holds[SessionRegistry.normalize(mac)] ?: emptySet())

    /**
     * True when [mac] has a session whose lifecycle is currently live.
     *
     * A hold can outlive its session when a disconnect is never observed (for example the whole
     * adapter is switched off): the registry slot stays with `Disconnected`/`Error`, so the hold has
     * to be cleaned up before it can block the next rising edge as "already held".
     */
    fun isLive(mac: String): Boolean =
        registry.snapshot(SessionRegistry.normalize(mac))?.lifecycle?.isLive() == true

    /**
     * Takes an app-scoped reference so the session survives the session screen (D-UI-3 exception).
     *
     * Callers keep their own reference: this acquires a second one and stays idempotent per
     * `(mac, reason)`. It is a no-op when the MAC has no live session.
     */
    fun hold(mac: String, reason: HoldReason) {
        val key = SessionRegistry.normalize(mac)
        if (registry.find(key) == null) return
        if (!record(key, reason)) return
        scope.launch { acquireHold(key, reason) }
    }

    /**
     * Suspending hold used by the auto-connect connector: opens the session through [open] when the
     * MAC is unknown and becomes its single app-scoped owner. Returns true when the hold is in place.
     *
     * A registry slot whose session is already dead is replaced rather than adopted: a leaked
     * reference elsewhere (or a double count) can keep such a slot alive after [releaseHold], and
     * silently reusing it would report "opened" while the device stays disconnected.
     */
    suspend fun holdOrOpen(mac: String, reason: HoldReason, open: suspend () -> ProtocolSession): Boolean {
        val key = SessionRegistry.normalize(mac)
        if (!record(key, reason)) return true
        val existing = registry.snapshot(key)
        if (existing != null && !existing.lifecycle.isLive()) {
            runCatching { registry.close(key) }
        }
        return runCatching { registry.acquire(key, open) }
            .fold(
                onSuccess = { true },
                onFailure = { unrecord(key, reason); false },
            )
    }

    /** Drops the hold for [mac] under [reason], releasing its registry reference once. */
    suspend fun releaseHold(mac: String, reason: HoldReason) {
        val key = SessionRegistry.normalize(mac)
        if (!unrecord(key, reason)) return
        runCatching { registry.release(key) }
    }

    /** Drops every hold carrying [reason]. */
    suspend fun releaseHolds(reason: HoldReason) {
        heldMacs(reason).forEach { key -> releaseHold(key, reason) }
    }

    /** Drops every background hold; called when "后台运行" is switched off. */
    suspend fun releaseBackgroundHolds() = releaseHolds(HoldReason.BACKGROUND_RUN)

    private suspend fun acquireHold(key: String, reason: HoldReason) {
        runCatching { registry.acquire(key) { error("session disappeared before hold") } }
            .onFailure { unrecord(key, reason) }
    }

    /** Records [reason] for [key]; returns true when this call added it. */
    private fun record(key: String, reason: HoldReason): Boolean {
        var added = false
        holds.compute(key) { _, existing ->
            val current = existing ?: emptySet()
            if (reason in current) {
                current
            } else {
                added = true
                current + reason
            }
        }
        return added
    }

    /** Removes [reason] for [key]; returns true when this call removed it. */
    private fun unrecord(key: String, reason: HoldReason): Boolean {
        var removed = false
        holds.computeIfPresent(key) { _, current ->
            when {
                reason !in current -> current
                current.size == 1 -> { removed = true; null }
                else -> { removed = true; current - reason }
            }
        }
        return removed
    }

    fun snapshot(mac: String): SessionSnapshot? = registry.snapshot(mac)

    fun refCount(mac: String): Int = registry.refCount(mac)

    fun refresh() {
        val snapshots = registry.snapshots()
        mutableSessions.value = snapshots.map { snapshot ->
            ManagedSession(
                mac = snapshot.mac,
                snapshot = snapshot,
                heldSince = heldSince[SessionRegistry.normalize(snapshot.mac)],
            )
        }
    }
}

/** True while the session is connected in any sense (used for the Home "活跃会话" stat). */
fun com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState.isLive(): Boolean = when (this) {
    com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState.Disconnected -> false
    is com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState.Error -> false
    com.fusion.melodyLinkNeo.device.runtime.DeviceLifecycleState.Created -> false
    else -> true
}

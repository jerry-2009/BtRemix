package com.fusion.melodyLinkNeo.device.session

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
    private val backgroundHolds = ConcurrentHashMap<String, Boolean>()

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

    /**
     * Takes an app-scoped reference so the session survives the session screen (D-UI-3 exception).
     *
     * The UI ViewModel still releases its own reference; this hold is what keeps the connection
     * alive while "后台运行" is on. It is a no-op when the MAC has no live session.
     */
    fun hold(mac: String) {
        val key = SessionRegistry.normalize(mac)
        if (registry.find(key) == null) return
        if (backgroundHolds.putIfAbsent(key, true) != null) return
        scope.launch {
            runCatching { registry.acquire(key) { error("session disappeared before background hold") } }
                .onFailure { backgroundHolds.remove(key) }
        }
    }

    /** Drops every background hold; called when "后台运行" is switched off. */
    fun releaseBackgroundHolds() {
        val keys = backgroundHolds.keys.toList()
        backgroundHolds.clear()
        keys.forEach { key -> scope.launch { runCatching { registry.release(key) } } }
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

package com.Fusion.Btremix.device.session

import com.Fusion.Btremix.core.classic.api.ClassicConnectionMonitor
import com.Fusion.Btremix.core.classic.api.ClassicDevice
import com.Fusion.Btremix.core.classic.api.ClassicLinkEvent
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.packages.DevicePackageRegistry
import com.Fusion.Btremix.device.registry.DeviceEntry
import com.Fusion.Btremix.device.runtime.ProtocolSession
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Structured outcome of the auto-session connector (HANDOFF_AUTO_SESSION.md §6 step 3). */
sealed interface AutoSessionEvent {
    val mac: String

    data class Opened(override val mac: String, val packageId: String?) : AutoSessionEvent
    data class Failed(override val mac: String, val error: Throwable?) : AutoSessionEvent
    data class Released(override val mac: String, val reason: String) : AutoSessionEvent
    data class Skipped(override val mac: String, val reason: String) : AutoSessionEvent
    data class Link(override val mac: String, val connected: Boolean) : AutoSessionEvent
}

/**
 * Drives [AutoSessionPolicy] against the live classic-link monitor (HANDOFF_AUTO_SESSION.md §5).
 *
 * `DeviceRegistry.devices` already only contains devices matched to an *enabled* package, so the
 * policy can use it directly. The connector owns the [SessionManager.holdOrOpen]-registered
 * [HoldReason.AUTO_CONNECT] reference per MAC: it never opens a second session when the UI or the
 * Melody bridge already owns one, and releasing an auto hold can never close a session another
 * front-end still holds.
 *
 * Retries run inside the launched open job, not in the event collector, so a device that fails to
 * connect does not stall the link-event stream.
 */
class AutoSessionConnector(
    private val monitor: ClassicConnectionMonitor,
    private val devices: StateFlow<List<DeviceEntry>>,
    private val sessions: SessionManager,
    private val packageRegistry: DevicePackageRegistry,
    private val enabled: StateFlow<Boolean>,
    private val open: suspend (mac: String, name: String?, definition: LoadedDeviceDefinition?) -> ProtocolSession,
    private val scope: CoroutineScope,
    private val policy: AutoSessionPolicy = AutoSessionPolicy(),
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val onEvent: (AutoSessionEvent) -> Unit = {},
) {
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private var eventsJob: Job? = null
    private var enabledJob: Job? = null
    private var devicesJob: Job? = null
    private var adapterJob: Job? = null

    fun start() {
        if (eventsJob == null) {
            eventsJob = scope.launch {
                monitor.events.collect { event ->
                    // Logged here (not inside `handle`) so catch-up seeds stay quiet.
                    onEvent(AutoSessionEvent.Link(SessionRegistry.normalize(event.address), event.connected))
                    handle(event)
                }
            }
        }
        if (enabledJob == null) {
            enabledJob = scope.launch {
                // StateFlow already conflates equal values, so this fires only on a real toggle.
                enabled.collect { on -> if (!on) releaseAll("disabled") }
            }
        }
        if (devicesJob == null) {
            // `DeviceRegistry.devices` fills asynchronously (bonded classic discovery + package
            // matching). Re-checking the links we have already seen lets a rising edge - or the
            // service-start seed - still open once the matching package shows up, instead of being
            // lost until the next transition.
            devicesJob = scope.launch {
                devices.collect { list ->
                    val held = liveHeldMacs()
                    connected.forEach { mac ->
                        if (mac in inFlight) return@forEach
                        if (mac in held) return@forEach
                        val entry = list.firstOrNull { it.key == mac } ?: return@forEach
                        dispatch(mac, list, isConnected = true, name = entry.name)
                    }
                }
            }
        }
        if (adapterJob == null) {
            // Switching the adapter off tears every link down at once, and the per-device
            // disconnect broadcasts are not guaranteed to arrive: drop all holds instead.
            adapterJob = scope.launch {
                monitor.adapterOff.collect {
                    connected.clear()
                    releaseAll("adapter_off")
                }
            }
        }
    }

    fun stop() {
        eventsJob?.cancel()
        eventsJob = null
        enabledJob?.cancel()
        enabledJob = null
        devicesJob?.cancel()
        devicesJob = null
        adapterJob?.cancel()
        adapterJob = null
    }

    /**
     * Treats the current connected set as a fresh rising edge; called on service start and on the
     * periodic catch-up pass to make up for links that were already up before the monitor was
     * registered. Devices that already have a live auto hold are skipped so the pass stays quiet.
     */
    suspend fun seed(connectedDevices: List<ClassicDevice>) {
        val held = liveHeldMacs()
        connectedDevices.forEach { device ->
            val mac = SessionRegistry.normalize(device.address)
            if (mac in inFlight) return@forEach
            if (mac in held) return@forEach
            handle(ClassicLinkEvent(device.address, device.name, connected = true))
        }
    }

    private suspend fun handle(event: ClassicLinkEvent) {
        val mac = SessionRegistry.normalize(event.address)
        if (event.connected) connected.add(mac) else connected.remove(mac)
        dispatch(mac, devices.value, event.connected, event.name)
    }

    private suspend fun dispatch(mac: String, list: List<DeviceEntry>, isConnected: Boolean, name: String?) {
        val held = liveHeldMacs()
        when (
            val decision = policy.decide(
                enabled = enabled.value,
                event = ClassicLinkEvent(mac, name, isConnected),
                devices = list,
                heldMacs = held,
                inFlightMacs = inFlight.toSet(),
            )
        ) {
            is AutoSessionDecision.Open -> {
                if (inFlight.add(mac)) {
                    scope.launch {
                        try {
                            openWithRetry(mac, decision.name, decision.packageId)
                        } finally {
                            inFlight.remove(mac)
                        }
                    }
                }
            }
            is AutoSessionDecision.Release -> release(mac, decision.reason)
            is AutoSessionDecision.Skip -> onEvent(AutoSessionEvent.Skipped(mac, decision.reason))
        }
    }

    private suspend fun openWithRetry(mac: String, name: String?, packageId: String) {
        var attempts = 0
        while (true) {
            attempts += 1
            val definition = packageRegistry.find(packageId)?.definition
            val opened = runCatching {
                sessions.holdOrOpen(mac, HoldReason.AUTO_CONNECT) { open(mac, name, definition) }
            }.onFailure { onEvent(AutoSessionEvent.Failed(mac, it)) }.getOrDefault(false)
            if (opened) {
                onEvent(AutoSessionEvent.Opened(mac, packageId))
                // A link that dropped while we were opening must not leave a zombie session.
                if (mac !in connected) release(mac, "link_lost_during_open")
                return
            }
            val waitMs = policy.retryDelayMs(attempts)
            if (waitMs == null || !enabled.value || mac !in connected) {
                onEvent(AutoSessionEvent.Skipped(mac, "retries_exhausted"))
                return
            }
            retryDelay(waitMs)
        }
    }

    private suspend fun release(mac: String, reason: String) {
        sessions.releaseHold(mac, HoldReason.AUTO_CONNECT)
        onEvent(AutoSessionEvent.Released(mac, reason))
    }

    private suspend fun releaseAll(reason: String) {
        sessions.heldMacs(HoldReason.AUTO_CONNECT).forEach { mac -> release(mac, reason) }
    }

    /**
     * The auto holds whose session is still live, after dropping the ones whose link died without a
     * disconnect we could observe.
     *
     * Switching system Bluetooth off can tear the link down without a usable disconnect (or the
     * broadcast never arrives on some ROMs); the recorded hold then made the next rising edge look
     * like a duplicate ("already held") and the session was never rebuilt. Releasing the dead hold
     * here is what lets the same device open again.
     */
    private suspend fun liveHeldMacs(): Set<String> {
        val held = sessions.heldMacs(HoldReason.AUTO_CONNECT)
        if (held.isEmpty()) return emptySet()
        val live = linkedSetOf<String>()
        held.forEach { mac ->
            if (mac in inFlight) {
                // Mid-open: the registry slot is published only once `open` returns, so "no live
                // session yet" is expected here and must never be read as a stale hold.
                live += mac
            } else if (sessions.isLive(mac)) {
                live += mac
            } else {
                sessions.releaseHold(mac, HoldReason.AUTO_CONNECT)
                onEvent(AutoSessionEvent.Released(mac, "stale_session"))
            }
        }
        return live
    }
}

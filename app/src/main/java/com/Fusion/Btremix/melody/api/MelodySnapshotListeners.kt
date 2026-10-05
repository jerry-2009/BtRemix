package com.Fusion.Btremix.melody.api

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny in-process fan-out for "a snapshot for [mac] was recorded" (M4.4 consistency link).
 *
 * The host panel already receives pushed snapshots through `IMelodyBridgeListener.onSnapshot`, but
 * M4.3c only used them to refill rows on its 1 Hz poll. M4.4 needs the panel to react to a value the
 * Compose side changed *after* that poll window, so the client notifies this registry on every
 * recorded snapshot and the panel hook debounces one re-apply from it. It is pure Kotlin (no
 * Android, no bridge) so the "add/remove/notify and never let one listener break the push" contract
 * is JVM tested.
 */
class MelodySnapshotListeners {

    fun interface Listener {
        fun onSnapshot(mac: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun add(listener: Listener) {
        if (!listeners.contains(listener)) listeners += listener
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    fun size(): Int = listeners.size

    /**
     * Notifies every listener; a throwing listener is dropped from this one round but never stops the
     * others (the push path must not fail because a diagnostic consumer misbehaved).
     */
    fun notifySnapshot(mac: String) {
        if (listeners.isEmpty()) return
        for (listener in listeners) {
            runCatching { listener.onSnapshot(mac) }
        }
    }
}

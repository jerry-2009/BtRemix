package com.Fusion.Btremix.melody.api

/**
 * Cold-start cache used by the Melody-side client (MELODY_BRIDGE_SPEC §5.5, §12 M2b).
 *
 * The provider inside `com.oplus.melody` can be asked about supported devices before BtRemix has been
 * started, and the panel can be opened while the bridge is momentarily disconnected. The client keeps
 * the last known answer here so those paths degrade to "the values we saw a moment ago" instead of to
 * "unknown", and so a caller can tell how stale that answer is.
 *
 * Pure Kotlin with an injected clock: the bookkeeping (change detection, staleness, last-write-wins)
 * is exactly the part that would otherwise hide behind a device and never be tested.
 */
class MelodyBridgeCache(private val clock: () -> Long = { System.currentTimeMillis() }) {

    data class CachedSnapshot(
        val mac: String,
        val lifecycle: String,
        val stateKeys: List<String>,
        val updatedAtMs: Long,
    )

    data class CachedSupport(
        val mac: String,
        val managed: Boolean,
        val name: String?,
        val productId: String?,
        val updatedAtMs: Long,
    )

    private val snapshots = LinkedHashMap<String, CachedSnapshot>()
    private val supports = LinkedHashMap<String, CachedSupport>()
    private var managed: List<String> = emptyList()

    /** Replaces the managed-MAC list; returns `true` when it actually changed (drives `onSupportChanged`). */
    @Synchronized
    fun noteManagedMacs(macs: Collection<String>): Boolean {
        val next = macs.map { MelodyMac.normalize(it) }.distinct().sorted()
        val changed = next != managed
        managed = next
        return changed
    }

    @Synchronized
    fun managedMacs(): List<String> = managed

    @Synchronized
    fun recordSnapshot(mac: String, lifecycle: String, stateKeys: Collection<String>): CachedSnapshot {
        val key = MelodyMac.normalize(mac)
        val entry = CachedSnapshot(
            mac = key,
            lifecycle = lifecycle,
            stateKeys = stateKeys.sorted(),
            updatedAtMs = clock(),
        )
        snapshots[key] = entry
        return entry
    }

    @Synchronized
    fun recordSupport(
        mac: String,
        managed: Boolean,
        name: String? = null,
        productId: String? = null,
    ): CachedSupport {
        val key = MelodyMac.normalize(mac)
        val entry = CachedSupport(
            mac = key,
            managed = managed,
            name = name,
            productId = productId,
            updatedAtMs = clock(),
        )
        supports[key] = entry
        return entry
    }

    @Synchronized
    fun snapshot(mac: String): CachedSnapshot? = snapshots[MelodyMac.normalize(mac)]

    @Synchronized
    fun support(mac: String): CachedSupport? = supports[MelodyMac.normalize(mac)]

    @Synchronized
    fun supports(): List<CachedSupport> = supports.values.toList()

    @Synchronized
    fun isSnapshotStale(mac: String, maxAgeMs: Long): Boolean {
        val entry = snapshot(mac) ?: return true
        return clock() - entry.updatedAtMs > maxAgeMs
    }

    @Synchronized
    fun forget(mac: String) {
        val key = MelodyMac.normalize(mac)
        snapshots.remove(key)
        supports.remove(key)
    }
}

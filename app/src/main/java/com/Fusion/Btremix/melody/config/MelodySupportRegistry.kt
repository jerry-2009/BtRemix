package com.Fusion.Btremix.melody.config

import com.Fusion.Btremix.core.classic.api.ClassicDevice
import com.Fusion.Btremix.core.classic.api.RfcommManager
import com.Fusion.Btremix.definition.api.DeviceMatchInput
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.MelodySectionDefinition
import com.Fusion.Btremix.definition.matcher.DefinitionMatcher
import com.Fusion.Btremix.definition.packages.DevicePackage
import com.Fusion.Btremix.melody.api.MelodyMac
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A paired classic device that a Definition with a `melody` section claims
 * (HANDOFF_MELODY_M3_PLAN.md §4 M3.1).
 */
data class MelodyManagedDevice(
    val mac: String,
    /** Name the system reports for the bonded device; `null` when it has none. */
    val name: String?,
    val definition: LoadedDeviceDefinition,
    val melody: MelodySectionDefinition,
) {
    val packageId: String get() = definition.manifest.id
}

/**
 * Answers "which paired devices does BtRemix want Melody to treat as supported?".
 *
 * The bridge used to key everything off live sessions, but support injection has to answer for a
 * device that is *paired yet not connected* - the panel asks the whitelist and builds the device list
 * before BtRemix ever opens a session (M3-D6). So this registry owns the second half of "managed":
 *
 * - rebuild the mapping whenever the device packages change (install/uninstall/reload), and on a slow
 *   timer so a pairing done while the app is running is picked up;
 * - for every bonded classic device, keep the highest-priority Definition whose `melody` section is
 *   present and whose matchers accept the device. A Definition **without** a `melody` section never
 *   manages anything, which is what keeps every pre-M3 package exactly as invisible as before.
 *
 * Matching reuses [DefinitionMatcher] over a neutral [DeviceMatchInput.Classic], i.e. the same rules
 * the BLE Explorer and SPP sessions use - there is no second matcher dialect here.
 */
class MelodySupportRegistry(
    private val packages: StateFlow<List<DevicePackage>>,
    private val rfcomm: RfcommManager,
    private val scope: CoroutineScope,
    /** Called after the managed set changes; the app wires this to the structured bridge log. */
    private val onManagedChanged: (List<String>) -> Unit = {},
) {
    private val lock = Mutex()

    /** Swapped as a whole on every refresh so a reader never observes a half-applied mapping. */
    @Volatile
    private var byMac: Map<String, MelodyManagedDevice> = emptyMap()

    private val managedFlow = MutableStateFlow<List<String>>(emptyList())

    /**
     * Emits whenever the managed set **or the Definition behind a MAC** changes (M4.2 robustness).
     *
     * A `StateFlow` of MACs alone cannot carry the second case: re-installing a device package with a
     * new `melody.panel` policy keeps the same MAC, so `managedMacsFlow` would not re-emit and the host
     * would keep answering from its cached projection envelope (the panel would keep the old hide
     * list). The bridge collects this flow to broadcast `onSupportChanged`, which invalidates that
     * cache, so a new dcpkg takes effect without restarting either process.
     */
    private val definitionChangesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val definitionChanges: SharedFlow<Unit> = definitionChangesFlow.asSharedFlow()
    private val jobs = mutableListOf<Job>()

    /** MAC + Definition identity of the last published mapping; a version bump counts as a change. */
    private var lastSignature: List<String> = emptyList()

    /** Normalised MACs the host should consider supported, in a stable order. */
    val managedMacsFlow: StateFlow<List<String>> = managedFlow.asStateFlow()

    /** One-shot start; safe to call once per process (the app scope owns the lifetime). */
    fun start(refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS) {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch { packages.collect { refresh() } }
        if (refreshIntervalMs > 0) {
            jobs += scope.launch {
                while (isActive) {
                    delay(refreshIntervalMs)
                    refresh()
                }
            }
        }
        scope.launch { refresh() }
    }

    fun stop() {
        jobs.forEach(Job::cancel)
        jobs.clear()
    }

    /** Normalised MACs that currently map to a Definition with a `melody` section. */
    fun managedMacs(): List<String> = managedFlow.value

    /** The managed device for [mac], or `null` when the MAC is unknown / has no `melody` Definition. */
    fun support(mac: String): MelodyManagedDevice? = byMac[MelodyMac.normalize(mac)]

    /** Recomputes the mapping from the current packages and the bonded classic devices. */
    suspend fun refresh() {
        val bonded = runCatching { rfcomm.bondedDevices() }
            .getOrElse { emptyList() }
        refreshFrom(bonded)
    }

    /**
     * Recomputes the mapping for an explicit bonded list.
     *
     * Production calls this through [refresh]; exposing it lets the bond-change receiver and tests
     * feed a list without going through the Bluetooth stack.
     */
    suspend fun refreshFrom(bonded: List<ClassicDevice>) = lock.withLock {
        val definitions = packages.value
            .map { it.definition }
            .filter { it.melody != null }
        val next = LinkedHashMap<String, MelodyManagedDevice>()
        bonded.filter { it.bonded }.forEach { device ->
            val matched = definitions.asSequence()
                .mapNotNull { definition ->
                    DefinitionMatcher.rank(definition, DeviceMatchInput.Classic(device))
                        ?.let { definition to it.priority }
                }
                .maxByOrNull { it.second }
                ?.first
                ?: return@forEach
            val mac = MelodyMac.normalize(device.address)
            next[mac] = MelodyManagedDevice(
                mac = mac,
                name = device.name,
                definition = matched,
                melody = requireNotNull(matched.melody),
            )
        }
        publish(next)
    }

    /**
     * Test seam: installs an explicit managed set without touching Bluetooth or the package registry.
     * The JVM suite drives the real matching path; the instrumented binder test only needs the map.
     */
    internal suspend fun seedManaged(devices: List<MelodyManagedDevice>) = lock.withLock {
        publish(devices.associateByTo(LinkedHashMap()) { MelodyMac.normalize(it.mac) })
    }

    /** Test seam counterpart of [seedManaged]. */
    internal suspend fun clearManaged() = seedManaged(emptyList())

    private fun publish(next: Map<String, MelodyManagedDevice>) {
        val macs = next.keys.sorted()
        byMac = next
        val signature = macs.map { mac ->
            val device = next.getValue(mac)
            mac + '@' + device.definition.manifest.id + '@' + device.definition.manifest.version
        }
        if (signature == lastSignature) return
        lastSignature = signature
        managedFlow.value = macs
        onManagedChanged(macs)
        definitionChangesFlow.tryEmit(Unit)
    }

    companion object {
        /** Slow enough to be free, fast enough to notice a pairing without restarting the app. */
        const val DEFAULT_REFRESH_INTERVAL_MS: Long = 30_000L
    }
}

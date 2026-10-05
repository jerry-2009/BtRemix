package com.Fusion.Btremix.melody.hook

import com.Fusion.Btremix.definition.api.MelodyHostVersions
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug-only replacements for the host version gate (M5.4 D-26). Both are read from the module
 * preferences in [com.Fusion.Btremix.melody.hook.MelodyBridgeEntry] and are empty in normal use:
 *
 * - [range] replaces the range a Definition declares (so a fail-branch can be exercised without a
 *   dcpkg that declares one);
 * - [version] replaces the `versionName` read from the host (so "unreadable" is reachable too).
 */
internal object MelodyHostGateOverrides {
    @Volatile
    var range: String? = null

    @Volatile
    var version: String? = null

    /**
     * M6 debug override for the host install fingerprint. Setting it to a different value makes the
     * next host start look like "Melody was updated", which exercises the full rescan + prompt path
     * without swapping the host APK.
     */
    @Volatile
    var installId: String? = null
}

/**
 * Host version gate (M5.4 D-21/D-25/D-26).
 *
 * The host `versionName` is read lazily on the first decision and cached for the process lifetime;
 * the range comes from the device's projection envelope (`definition.hostVersions`, i.e. the dcpkg's
 * `melody.support.hostVersions`). An absent or unparseable range means "no restriction", which is the
 * shipped 1.4.0 behaviour.
 *
 * A rejection is reported once per `hook`/`mac` pair as `melody.host.version_unsupported`, and the
 * caller must fail open for that device: no hide, no projection, no redirect. Observation and the
 * bridge keep running so a new host version can still be dumped (D-25).
 */
internal class MelodyHostGate(
    private val log: MelodyLog,
    private val versionProvider: () -> String?,
) {

    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** Read at most once per process; [MelodyHostGateOverrides.version] wins when set. */
    private val hostVersion: String? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MelodyHostGateOverrides.version ?: runCatching(versionProvider).getOrNull()
    }

    /**
     * True when [mac] may be injected for. [declaredRange] is the envelope's range; a debug override
     * ([MelodyHostGateOverrides.range]) replaces it entirely when present.
     */
    fun allows(mac: String, hook: String, declaredRange: String?): Boolean {
        val spec = MelodyHostGateOverrides.range ?: declaredRange ?: return true
        val range = MelodyHostVersions.parse(spec) ?: return true
        val version = hostVersion
        if (range.matches(version)) return true
        if (reported.add("$hook/$mac")) {
            log.event(
                "melody.host.version_unsupported",
                "hook" to hook,
                "mac" to mac,
                "version" to (version ?: "?"),
                "range" to range.spec,
                "reason" to if (MelodyHostVersions.isReadable(version)) "out_of_range" else "unreadable",
            )
        }
        return false
    }
}

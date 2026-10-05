package com.Fusion.Btremix.melody.hook.injection

import android.os.SystemClock
import com.Fusion.Btremix.melody.api.MelodyAncNoiseIndex
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.MelodyAnchorSession
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * M5.1 follow-up: project our ANC index into the btsdk noise value the *device-centre card* is built from.
 *
 * The detail page and OneSpace are built from `EarphoneDTO`, which [MelodyEarphoneInjection] already
 * answers from the bridge. The device-centre card is not: when the host rebuilds its card rows it calls
 * `i9/c.e(app, sqlId, sdkDeviceInfo, CurrentNoiseModeInfo, modes, res)`, which reads
 * `CurrentNoiseModeInfo.getCurrentNoiseReductionModeIndex()` to decide which mode row is checked
 * (`docs/melody-capability-map.md` §8.1). That value comes from the host's own btsdk session - suppressed
 * by M3.4 and bypassed by the M5 redirect - so after a click the card is rebuilt with the *old* mode and
 * `Ba/r.g(...)`, the only post-write callback the host runs, turned out to be tracking-only.
 *
 * Hooking the computed getter (rather than a carrier field) is what makes this robust: the host may hold
 * a `CurrentNoiseModeInfo` snapshot for a while, but the index is recomputed on every read, so the
 * override applies even to a stale carrier. `CurrentNoiseModeInfo` has no MAC, so the answer is only
 * given when exactly one device is managed ([MelodyAncNoiseIndex], fail-open otherwise).
 *
 * Everything is fail-open: a missing anchor, no bridge, an ambiguous managed set or a getter that throws
 * leaves the host's own value untouched.
 */
internal class MelodyAncNoiseInfoInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    /** Last `(mac, index)` logged, so the hot getter does not flood logcat. */
    private val lastLogged = ConcurrentHashMap<String, Int>()

    /** Last managed-device count that was reported as ambiguous. */
    @Volatile
    private var lastAmbiguous = -1

    /** Short-lived resolution cache: the getter is read while the card is built, in bursts. */
    @Volatile
    private var cached: Pair<Long, MelodyAncNoiseIndex.Target?>? = null

    fun install() {
        val cls = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.BTSDK_NOISE_INFO, loader)
        if (cls == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "class" to INFO_CLASS)
            return
        }
        val getter = Reflect.findMethod(cls, GETTER, emptyArray())
        if (getter == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "class" to cls.name, "target" to GETTER)
            return
        }
        module.hook(getter).intercept(XposedInterface.Hooker { chain ->
            val official = chain.proceed()
            runCatching { target()?.index }.getOrNull() ?: official
        })
        log.event(
            "melody.anchor.hooked",
            "hook" to HOOK,
            "class" to cls.name,
            "method" to getter.name,
        )
    }

    /** The projected index for the managed device, or `null` when the host's own value must stand. */
    private fun target(): MelodyAncNoiseIndex.Target? {
        val now = SystemClock.elapsedRealtime()
        cached?.let { (stamp, value) -> if (now - stamp <= RESOLVE_TTL_MS) return value }
        val client = MelodyBridgeClients.existing() ?: return null
        val managed = runCatching { client.managedMacsFast() }.getOrDefault(emptyList())
        val resolved = MelodyAncNoiseIndex.resolve(managed) { mac ->
            runCatching { client.currentAncIndexFast(mac) }.getOrNull()
        }
        cached = now to resolved
        if (resolved == null) {
            noteAmbiguous(managed.map { it.trim() }.distinct().size)
            return null
        }
        noteProjected(resolved)
        return resolved
    }

    /**
     * One line when the card's noise value is answered from our projection, so a device run can show the
     * device-centre card path working (or not) without a debugger. Deduplicated per `(mac, index)`.
     */
    private fun noteProjected(target: MelodyAncNoiseIndex.Target) {
        if (lastLogged.put(target.mac, target.index) == target.index) return
        log.event("melody.anc.noiseinfo", "mac" to target.mac, "index" to target.index, "source" to "btsdk")
    }

    /**
     * Reported once per managed-device-count change: with two or more managed devices the owner of the
     * btsdk noise object is unknown, so the host's own (stale) value is left in place on purpose.
     */
    private fun noteAmbiguous(count: Int) {
        if (count <= 1 || lastAmbiguous == count) return
        lastAmbiguous = count
        log.event("melody.anc.noiseinfo.skip", "reason" to "ambiguous", "managed" to count)
    }

    private companion object {
        const val HOOK = "inject.anc_noiseinfo"
        const val INFO_CLASS = "com.oplus.melody.btsdk.api.data.CurrentNoiseModeInfo"
        const val GETTER = "getCurrentNoiseReductionModeIndex"

        /** Same short window the other hot-projection hooks use; a card build is a burst of reads. */
        const val RESOLVE_TTL_MS = 1_000L
    }
}

package com.fusion.melodyLinkNeo.melody.hook.injection

import android.os.SystemClock
import com.fusion.melodyLinkNeo.melody.api.MelodyDeviceInfoProjection
import com.fusion.melodyLinkNeo.melody.api.MelodyEarphoneBattery
import com.fusion.melodyLinkNeo.melody.api.MelodyEarphoneProjection
import com.fusion.melodyLinkNeo.melody.api.MelodyMac
import com.fusion.melodyLinkNeo.melody.hook.MelodyLog
import com.fusion.melodyLinkNeo.melody.hook.MelodyAnchorSession
import com.fusion.melodyLinkNeo.melody.hook.Reflect
import com.fusion.melodyLinkNeo.melody.hook.bridge.MelodyBridgeClient
import com.fusion.melodyLinkNeo.melody.hook.bridge.MelodyBridgeClients
import com.fusion.melodyLinkNeo.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * M4.3a detail/OneSpace header projection (`HANDOFF_MELODY_M4_PLAN.md` §3 M4.3a, decision D-6).
 *
 * The two headers read `com.oplus.melody.model.repository.earphone.EarphoneDTO` to decide "已连接 +
 * 电量" vs "未连接 / 立即连接", and that object has no notion of the BtRemix session - which is exactly
 * why the M3.4 `DeviceInfo` work did not light the header up. This hook intercepts the DTO's getters and
 * answers them from the bridge's cached session projection instead:
 *
 * - the connection states follow the session lifecycle ([MelodyDeviceInfoProjection.profileStateOf]),
 *   so `Ready` shows the native connected header and a dropped session falls back to the native
 *   "未连接 / 立即连接";
 * - the battery levels are the `battery.left/right/case` states the BtRemix page already shows, and a
 *   missing level keeps whatever the host had instead of writing `0`.
 *
 * One hook covers both pages: `DetailMainActivity` and `OneSpaceDetailActivity` are built from the same
 * DTO (D-6). Only MACs in the managed set are touched, and every step - class, getter, bridge, envelope -
 * degrades to "return the official value" (fail-open, §7). No write path is installed: the header is
 * display-only in M4.3a, and the native ANC click stays on the host's own path until M5 (D-9).
 *
 * M4.3b D-15 puts the ANC **strength** on the host's own「降噪效果」row instead: the injected mode table
 * carries a `childrenMode` list, and the index projected here is the selected child's `protocolIndex`,
 * which is what the host writes and what it resolves back to the parent cell. The ANC *click* still
 * stays on the host's own path until M5 (D-9): nothing here writes device state.
 *
 * The getters are read far more often than the state changes (the header, the ANC VO builder and the
 * device card all poll the DTO), so the projection itself comes from [MelodyBridgeClient.earphoneFast]
 * - a pure cache read - and the diagnostic line is deduplicated per MAC and value.
 */
internal class MelodyEarphoneInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    @Volatile
    private var managed: Pair<Long, Set<String>>? = null

    /** Last projected (connection, battery) fingerprint per MAC, so the hot getters log once per change. */
    private val lastProjected = ConcurrentHashMap<String, String>()

    /** Last skip reason per MAC, so a hot getter that cannot project yet logs once instead of per call. */
    private val lastSkip = ConcurrentHashMap<String, String>()

    fun install() {
        val cls = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.DTO_EARPHONE, loader)
        if (cls == null) {
            log.event("melody.anchor.missing", "hook" to "inject.header", "class" to DTO_CLASS)
            return
        }
        var hooked = 0
        for (getter in MelodyEarphoneAdapter.GETTERS) {
            val method = Reflect.findMethod(cls, getter, emptyArray())
            if (method == null) {
                log.event("melody.anchor.missing", "hook" to "inject.header", "target" to getter)
                continue
            }
            module.hook(method).intercept(XposedInterface.Hooker { chain ->
                val official = chain.proceed()
                runCatching { override(chain.thisObject, getter) }.getOrNull() ?: official
            })
            hooked++
        }
        log.event("melody.anchor.hooked", "hook" to "inject.header", "class" to cls.name, "getters" to hooked)
    }

    /**
     * Returns the projected value for one getter, or `null` when the host's own answer must stand (not
     * our device, no bridge, no envelope, or a field the projection has nothing to say about).
     */
    private fun override(target: Any?, getter: String): Any? {
        val projection = projectionOf(target) ?: return null
        return MelodyEarphoneAdapter.overrideFor(getter, projection)
    }

    /**
     * The shared "is this our device and what does the bridge project" lookup. Returns `null` (and logs
     * one reason) whenever the host's own answer must stand.
     */
    private fun projectionOf(target: Any?): MelodyEarphoneProjection? {
        val mac = macOf(target) ?: return noteSkip("?", "mac")
        val client = MelodyBridgeClients.existing() ?: return noteSkip(mac, "client")
        if (!isManaged(client, mac)) return noteSkip(mac, "not_managed")
        // The client logs its own reason (`no_snapshot` / `no_envelope`) when it cannot project.
        val projection = client.earphoneFast(mac) ?: return null
        lastSkip.remove(mac)
        logProjectedOnce(mac, projection)
        return projection
    }

    /**
     * One line per MAC and reason. Without this the only observable failure mode is "nothing happens",
     * which is exactly what a missing anchor, an unreadable envelope and a cold bridge cache all look
     * like from logcat.
     */
    private fun noteSkip(mac: String, reason: String): MelodyEarphoneProjection? {
        if (lastSkip.put(mac, reason) != reason) {
            log.event("melody.panel.header.skip", "hook" to "inject.header", "mac" to mac, "reason" to reason)
        }
        return null
    }

    /** The DTO's own address field; the getter fallback keeps this working if a release hides it. */
    private fun macOf(target: Any?): String? {
        if (target == null) return null
        val raw = (Reflect.readField(target, "macAddress", "mMacAddress") as? String)
            ?: Reflect.callString(target, "getMacAddress")
        return raw?.trim()?.takeIf(MelodyMac::isMacAddress)?.let(MelodyMac::normalize)
    }

    /**
     * The managed set is a persisted list whose reader is cheap but still a copy per call, and the DTO
     * getters are polled continuously - the same short TTL M3.4 uses keeps this allocation-free.
     */
    private fun isManaged(client: MelodyBridgeClient, mac: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val cached = managed
        if (cached != null && now - cached.first <= MANAGED_TTL_MS) return mac in cached.second
        val next = runCatching { client.managedMacsFast().toSet() }.getOrDefault(emptySet())
        managed = now to next
        return mac in next
    }

    /**
     * One line per MAC per projected value. A connection change is what the operator looks for; the
     * battery levels are included so a "connected but 0%" report can tell "no state" from "level 0".
     */
    private fun logProjectedOnce(mac: String, projection: MelodyEarphoneProjection) {
        val fingerprint = "${projection.connectionState}|${batteryLabel(projection.battery)}|" +
            "${projection.noiseModeIndex}|${projection.ancModeMatched}"
        if (lastProjected.put(mac, fingerprint) == fingerprint) return
        log.event(
            "melody.panel.header.projected",
            "mac" to mac,
            "connected" to projection.connected,
            "state" to projection.connectionState,
            "battery" to batteryLabel(projection.battery),
        )
        // M4.3b Step 1 diagnostic: which native ANC slot the panel should highlight, and whether the
        // Definition's live mode was actually found in the mode table (`matched=false` = the Off
        // fallback, see docs/melody-capability-map.md §7.1.3).
        if (projection.noiseModeIndex != null || projection.ancModeMatched) {
            log.event(
                "melody.panel.anc.index",
                "mac" to mac,
                "index" to (projection.noiseModeIndex ?: -1),
                "matched" to projection.ancModeMatched,
            )
        }
    }

    private fun batteryLabel(battery: MelodyEarphoneBattery?): String = battery?.let {
        "${it.left ?: "-"}/${it.right ?: "-"}/${it.box ?: "-"}"
    } ?: "none"

    private companion object {
        const val DTO_CLASS = "com.oplus.melody.model.repository.earphone.EarphoneDTO"

        /** Same window M3.4 uses: long enough to cover a burst of getter calls, short enough to notice a pairing. */
        const val MANAGED_TTL_MS = 2_000L
    }
}

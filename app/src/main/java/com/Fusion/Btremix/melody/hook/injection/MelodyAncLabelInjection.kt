package com.Fusion.Btremix.melody.hook.injection

import com.Fusion.Btremix.melody.api.MelodyAncPolicy
import com.Fusion.Btremix.melody.api.MelodyAncRenderOrder
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.MelodyAnchorSession
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClient
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * D-12 mode-label hook (`HANDOFF_MELODY_M4_PLAN.md` §3 M4.3b).
 *
 * The host hard-codes the title of every ANC mode by `modeType` (`NoiseReductionItem.updateActionView`),
 * and its vocabulary has no slot for a Definition's own wording - a `wind` mode would render as
 * "Adaptive". Instead of touching host resources, this hook rewrites the incoming mode list at the one
 * place the host collects it, `DeviceControlWidget.b(ArrayList)`, replacing each `ModeItem.name` with
 * the Definition's `melody.anc.modes[].label`.
 *
 * The rewrite is **positional**: `ModeItem.id` is the cell position the host assigned
 * (`createModeItem` writes `String.valueOf(counter)`), not the `modeType`, so looking a label up by id
 * rotates the titles by one slot (measured on 17.6.3: 降噪/自适应/关闭/通透 became 降噪/Off/Ambient/通透).
 * The cell order is the host's own `e9.r.a(uiVersion)`, filtered to the modeTypes the injected table
 * carries ([MelodyAncRenderOrder]).
 *
 * Scope is deliberately narrow (D-12 / §8 item 15): the rewrite only runs when exactly one managed
 * device carries an ANC table, i.e. while that one device's panel is being rendered. Several managed
 * devices with a table at once are ambiguous without a per-screen MAC, so the hook logs
 * `melody.panel.anc.label ambiguous` once and leaves the official texts alone. Every lookup is
 * name/shape based and fail-open: a missing anchor, an unreadable item or a missing label changes
 * nothing.
 */
internal class MelodyAncLabelInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    /** Last applied label fingerprint per managed MAC, so the hot render path logs once per change. */
    private val lastApplied = ConcurrentHashMap<String, String>()

    /** Last "the incoming list is not our ANC row" shape per MAC, so it logs once. */
    private val lastShape = ConcurrentHashMap<String, String>()

    /** One "cannot tell which device this is" line until the managed set changes. */
    @Volatile
    private var ambiguousLogged = false

    fun install() {
        val cls = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.PANEL_DEVICE_CONTROL_WIDGET, loader)
        if (cls == null) {
            log.event("melody.anchor.missing", "hook" to "inject.anc.label", "class" to WIDGET_CLASS)
            return
        }
        val method = Reflect.findMethod(cls, "b", arrayOf(ArrayList::class.java))
        if (method == null) {
            log.event(
                "melody.anchor.missing",
                "hook" to "inject.anc.label",
                "class" to cls.name,
                "method" to "b(ArrayList)",
            )
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            runCatching { rewrite(chain.args.getOrNull(0)) }
                .onFailure { log.warn("melody.panel.anc.label_failed", it) }
            chain.proceed()
        })
        log.event("melody.anchor.hooked", "hook" to "inject.anc.label", "class" to cls.name, "method" to method.name)
    }

    /**
     * Rewrites every `ModeItem` the host is about to render for the single managed device with an ANC
     * table. The list is the host's own object, so a write here is exactly what the widget then builds
     * its cells from.
     */
    private fun rewrite(arg: Any?) {
        val items = arg as? List<*> ?: return
        if (items.isEmpty()) return
        val client = MelodyBridgeClients.existing() ?: return
        val target = targetOf(client) ?: return
        val order = MelodyAncRenderOrder.of(target.policy.uiVersion, target.policy.modes)
        // `ModeItem.id` is the cell position, so the labels must be applied positionally. A size
        // mismatch means this is not the ANC row we think it is (or the host filtered a cell out) -
        // leaving it untouched is the fail-open choice.
        if (order.size != items.size) {
            val shape = "${items.size}/${order.size}"
            if (lastShape.put(target.mac, shape) != shape) {
                log.event(
                    "melody.panel.anc.label",
                    "mac" to target.mac,
                    "reason" to "shape",
                    "items" to items.size,
                    "expected" to order.size,
                )
            }
            return
        }
        val fingerprint = StringBuilder()
        var rewritten = 0
        order.forEachIndexed { index, modeType ->
            val label = target.policy.labelOf(modeType) ?: return@forEachIndexed
            if (writeName(items[index], label)) {
                rewritten++
                fingerprint.append(index).append(':').append(modeType).append('=').append(label).append(';')
            }
        }
        if (rewritten == 0) return
        val key = fingerprint.toString()
        if (lastApplied.put(target.mac, key) == key) return
        lastShape.remove(target.mac)
        log.event("melody.panel.anc.label", "mac" to target.mac, "count" to rewritten, "labels" to key)
    }

    private data class Target(val mac: String, val policy: MelodyAncPolicy)

    /**
     * The managed device whose ANC table should own the texts. Exactly one managed device with a
     * non-empty table is actionable; zero means "no ANC to label", several means the screen's MAC is
     * not discoverable here, so the official texts stay (D-12 scope).
     */
    private fun targetOf(client: MelodyBridgeClient): Target? {
        val managed = runCatching { client.managedMacsFast() }.getOrDefault(emptyList())
        val withAnc = managed.filter { !client.ancFast(it).isEmpty }
        return when (withAnc.size) {
            0 -> null
            1 -> withAnc.first().let { mac -> Target(mac, client.ancFast(mac)) }
            else -> {
                if (!ambiguousLogged) {
                    ambiguousLogged = true
                    log.event("melody.panel.anc.label", "reason" to "ambiguous", "managed" to withAnc.size)
                }
                null
            }
        }
    }

    /** `createModeItem` writes the title through the `s(String)` setter; fields are the fallback. */
    private fun writeName(item: Any?, label: String): Boolean {
        if (item == null) return false
        if (Reflect.invokeSingleArg(item, "s", label)) return true
        if (Reflect.invokeSingleArg(item, "setName", label)) return true
        return Reflect.writeField(item, arrayOf("name", "mName"), label)
    }

    private companion object {
        const val WIDGET_CLASS = "com.oplus.melody.ui.widget.devicecontrol.DeviceControlWidget"
    }
}

package com.Fusion.Btremix.melody.hook.injection

import android.content.Context
import android.util.AttributeSet
import com.Fusion.Btremix.definition.api.MelodyPanelDefinition
import com.Fusion.Btremix.melody.api.MelodyPanelGroup
import com.Fusion.Btremix.melody.api.MelodyPanelPolicy
import com.Fusion.Btremix.melody.api.MelodyPanelRow
import com.Fusion.Btremix.melody.api.MelodyPanelRowKind
import com.Fusion.Btremix.melody.hook.PreferenceTree
import com.Fusion.Btremix.melody.hook.Reflect
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap

/**
 * Reads the live display text of one Definition state (the panel hook's `MelodyBridgeClient` in
 * production). Injected so the applier can be exercised on the JVM without an Android client.
 */
internal fun interface MelodyRowStateText {
    fun text(state: String): String?
}

/**
 * One structured `melody.panel.*` line. A fun interface keeps the applier free of the module's
 * Xposed-backed `MelodyLog` (which is `compileOnly`), so its wiring can be unit tested on the JVM.
 */
internal fun interface MelodyGroupLog {
    fun event(name: String, fields: List<Pair<String, Any?>>)
}

/**
 * Builds and maintains BtRemix's self-built「高级功能」group on the Melody detail page (M4.3c,
 * `HANDOFF_MELODY_M4_PLAN.md` §3 M4.3c, decision D-8).
 *
 * The group is one host category (key `melody_bridge_advanced`) inserted at the top level of the
 * `PreferenceScreen`, right after the official `sound` group, holding one native-styled row per routed
 * envelope row (`MelodyPanelGroup`). Nothing here decides *which* controls belong to the group - the
 * projection builder already routed them - so this file is limited to reflection:
 *
 * - the COUI preference classes are R8-minified inside `com.oplus.melody`, so every accessor and every
 *   container write is name/shape based with the field fallback M1's `PreferenceTree` already uses;
 * - `PreferenceGroup.addPreference` is renamed in this build (M4.3b D-14), so insertion is
 *   **effect-based**: candidate single-argument methods are invoked until `childrenOf(container)`
 *   actually contains the new row. That is the same conclusion D-14 reached on device;
 * - every step is fail-open. A missing class, refusal to add, or blank value only produces a log line;
 *   the official panel is never touched.
 *
 * The row values are re-filled from the pushed session snapshot on every panel tick, so a host refresh
 * that rebuilds the list is covered by M4.2's existing 1 Hz re-apply (the group is re-created when it
 * disappeared, updated in place otherwise). Row click handling is M4.4; this slice renders and
 * backfills only.
 *
 * Scope (M4.5c on-device finding): the group is inserted on the Melody **detail page only** - see
 * [MelodyPanelGroup.allowsAdvancedGroup]. The card-style pages (OneSpace / device card) run the same
 * applier through the shared panel hook, and an inserted COUI category there showed up as a stray
 * "高级功能" block; those screens now insert nothing and get any previously inserted card hidden.
 */
internal object MelodyPanelGroupApplier {

    /** Last effective group fingerprint per screen, so the summary log fires only on a change. */
    private val lastFingerprint = ConcurrentHashMap<String, String>()

    /** Last "could not insert" reason per screen, deduplicated like the policy diagnostics. */
    private val lastFailure = ConcurrentHashMap<String, String>()

    /**
     * The rows we inserted per category. The host's `getPreferenceCount`/`getPreference` pair is
     * R8-stripped on COUI categories (D-14), so if its children-list fallback ever fails to expose a
     * freshly inserted row the 1 Hz re-apply would insert a fresh copy each tick. Weak keys/values keep
     * this from retaining a destroyed page.
     */
    private val rows = WeakHashMap<Any, MutableMap<String, Any>>()

    /**
     * The category inserted per screen. The screen's own enumeration is authoritative, but if it ever
     * fails to expose a child we just added, this stops the 1 Hz re-apply from inserting a fresh copy -
     * the category is only reused while `getParent` positively confirms it is still attached.
     */
    private val groups = WeakHashMap<Any, Any>()

    /** Last per-row diagnostic fingerprint, so the 1 Hz re-apply logs only on a real change. */
    private val lastItemFingerprint = ConcurrentHashMap<String, String>()

    /**
     * Row object -> the policy row its click listener was built from (M4.4). The 1 Hz re-apply must
     * not churn a fresh proxy onto a stable row; it only re-binds when the row object or the routed
     * row changed (a host rebuild, or a new envelope).
     */
    private val boundRows = WeakHashMap<Any, MelodyPanelRow>()

    /**
     * Ensures the group matches [policy] and returns `true` when the screen changed (a row was added or
     * re-filled), so the caller can fold it into its own `melody.panel.applied` accounting.
     */
    fun apply(
        screenId: String,
        screen: Any,
        context: Context?,
        policy: MelodyPanelPolicy,
        stateText: MelodyRowStateText,
        mac: String,
        loader: ClassLoader,
        log: MelodyGroupLog,
        clickBinder: MelodyRowClickBinder? = null,
    ): Boolean {
        // M4.5c: the self-built card belongs to the detail page. On every other relevant page we take
        // the same path as "the policy dropped the group": insert nothing, hide a card left behind by
        // an earlier pass, and say why once per screen.
        val group = policy.group
        val roots = runCatching { PreferenceTree.childrenOf(screen) }.getOrDefault(emptyList())
        val existing = roots.firstOrNull { keyOf(it) == MelodyPanelGroup.ADVANCED_KEY }
            ?: groups[screen]?.takeIf { strictlyAttachedTo(it, screen) }

        if (group != null && !MelodyPanelGroup.allowsAdvancedGroup(screenId)) {
            if (existing != null && !isVisible(existing)) return false
            if (existing != null) {
                setVisible(existing, false)
                rows.remove(existing)
                groups.remove(screen)
            }
            noteFailure(screenId, "screen_not_allowed", log, mac)
            return existing != null
        }

        if (group == null || group.isEmpty) {
            // A policy that dropped its group (e.g. new dcpkg) must not leave a stale card behind; a
            // hidden row is the same fail-open write M4.2 uses, and the host rebuild drops it anyway.
            if (existing != null && !isVisible(existing)) return false
            if (existing != null) setVisible(existing, false)
            if (existing != null) {
                rows.remove(existing)
                groups.remove(screen)
            }
            logGroup(screenId, mac, rows = 0, log, reason = "none")
            return existing != null
        }

        val category = existing ?: createGroup(screenId, screen, roots, context, loader, log)
            ?: run {
                noteFailure(screenId, "add_refused", log, mac)
                return false
            }
        groups[screen] = category

        clearFailure(screenId)
        setVisible(category, true)
        if (currentTitle(category) != group.title) setTitle(category, group.title)

        var changed = existing == null
        val wanted = group.rows.map { it.key }.toSet()
        group.rows.forEachIndexed { index, row ->
            var builtHere = false
            var addedHere = false
            val preference = findRow(category, row.key) ?: buildRow(screenId, category, context, row, loader, log)?.also { built ->
                builtHere = true
                addedHere = addPreference(category, built)
                if (addedHere) changed = true
            }
            if (preference != null) {
                rememberRow(category, row.key, preference)
                val filled = fillRow(preference, row, index, stateText)
                // M4.4: interactive rows get the click link; read-only rows stay inert. Fail-open -
                // a bind failure only logs (`melody.panel.click reason=...`).
                if (row.kind.interactive && clickBinder != null && boundRows[preference] !== row) {
                    val attached = runCatching {
                        clickBinder.bind(preference, row, contextOf(preference) ?: context)
                    }.getOrDefault(false)
                    if (attached) boundRows[preference] = row
                }
                logItem(screenId, mac, row, preference, builtHere, addedHere, filled, log)
            } else {
                logItem(screenId, mac, row, null, built = false, added = false, filled = null, log = log)
            }
        }
        // Rows that disappeared from the policy are hidden rather than removed (the host may rebuild).
        for (child in runCatching { PreferenceTree.childrenOf(category) }.getOrDefault(emptyList())) {
            val key = keyOf(child)
            if (key.isNotEmpty() && key.startsWith(MelodyPanelDefinition.CUSTOM_KEY_PREFIX) && key !in wanted) {
                setVisible(child, false)
            }
        }
        notifyChanged(category)
        logGroup(screenId, mac, rows = group.rows.size, log)
        return changed
    }

    // --- creation ---------------------------------------------------------------------------------

    private fun createGroup(
        screenId: String,
        screen: Any,
        roots: List<Any>,
        context: Context?,
        loader: ClassLoader,
        log: MelodyGroupLog,
    ): Any? {
        val cls = CATEGORY_CLASSES.firstNotNullOfOrNull { Reflect.loadClass(it, loader) } ?: return null
        val themed = contextOf(screen) ?: context
        val category = construct(cls, themed) ?: return null
        setKey(category, MelodyPanelGroup.ADVANCED_KEY)
        val sound = roots.firstOrNull { keyOf(it) == SOUND_GROUP_KEY }
        val order = orderOf(sound)?.plus(1)
            ?: (roots.mapNotNull(::orderOf).maxOrNull()?.plus(1) ?: 0)
        setOrder(category, order)
        // Same white-card look as the official groups: clone the layout ids of a sibling row so the
        // COUI inflation path is identical. Purely cosmetic - a failure leaves the default layout.
        // Prefer a sibling category (`sound`) so the new card clones the white-card layout; fall back to
        // a plain row if no group enumerates.
        val template = sound
            ?: roots.firstOrNull { PreferenceTree.isGroup(it) }
            ?: roots.firstOrNull { !PreferenceTree.isGroup(it) }
        if (template != null) copyLayoutResources(template, category)
        if (!addPreference(screen, category)) {
            log.event(
                "melody.panel.group",
                listOf(
                    "screen" to screenId,
                    "key" to MelodyPanelGroup.ADVANCED_KEY,
                    "reason" to "insert_refused",
                ),
            )
            return null
        }
        log.event(
            "melody.panel.group.create",
            listOf(
                "screen" to screenId,
                "key" to MelodyPanelGroup.ADVANCED_KEY,
                "cls" to cls.name,
                "order" to order,
                "template" to template?.javaClass?.name,
            ),
        )
        notifyChanged(screen)
        return category
    }

    private fun buildRow(
        screenId: String,
        category: Any,
        context: Context?,
        row: MelodyPanelRow,
        loader: ClassLoader,
        log: MelodyGroupLog,
    ): Any? {
        val themed = contextOf(category) ?: context
        val cls = rowClass(row.kind, loader) ?: run {
            logRowFailure(screenId, row, "class_missing", null, log)
            return null
        }
        val preference = construct(cls, themed) ?: run {
            logRowFailure(screenId, row, "construct_failed", cls.name, log)
            return null
        }
        setKey(preference, row.key)
        setTitle(preference, row.title)
        // Clone the layout only from a sibling of the *same* class: a plain row's layout has no switch
        // widget, so copying it onto a `COUISwitchPreference` would hide the switch (measured symptom).
        val template = if (row.kind == MelodyPanelRowKind.SEGMENTED) {
            // Native「降噪效果」look (M4.4 follow-up): the current value sits on the right of a jump row,
            // so the layout/arrow are taken from a host row that already renders that style.
            jumpTemplate(rootOf(category))
        } else {
            rootTemplateOf(category, cls)
        }
        copyLayoutResources(template, preference)
        if (row.kind == MelodyPanelRowKind.SEGMENTED) copyJump(template, preference)
        return preference
    }

    private fun rowClass(kind: MelodyPanelRowKind, loader: ClassLoader): Class<*>? = when (kind) {
        MelodyPanelRowKind.SWITCH ->
            Reflect.loadClass(SWITCH_CLASS, loader) ?: Reflect.loadClass(ROW_CLASS, loader)
        // COUI's jump preference is the same row style as the host's「降噪效果」row and is a real
        // `Preference`, so a self-built equalizer row can match it.
        MelodyPanelRowKind.SEGMENTED ->
            Reflect.loadClass(JUMP_CLASS, loader) ?: Reflect.loadClass(ROW_CLASS, loader)
        else -> Reflect.loadClass(ROW_CLASS, loader)
    }

    /** The outermost container of [start] (the `PreferenceScreen`), used to look for a style donor. */
    private fun rootOf(start: Any): Any {
        var current = start
        var guard = 0
        while (guard++ < MAX_PARENT_HOPS) {
            val parent = Reflect.call(current, "getParent") ?: return current
            current = parent
        }
        return current
    }

    /**
     * A host row that already renders the "value on the right + jump arrow" style: a `COUIPreference`
     * whose `mJumpRes` drawable is set. The first such row in the tree donates its layout resources and
     * arrow, so the self-built row looks native without hardcoding any build-specific resource id.
     */
    private fun jumpTemplate(root: Any): Any? {
        val queue = ArrayDeque<Any>()
        queue += root
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_TREE_SCAN) {
            val node = queue.removeFirst()
            if (Reflect.readField(node, "mJumpRes") != null) return node
            for (child in runCatching { PreferenceTree.childrenOf(node) }.getOrDefault(emptyList())) {
                queue += child
            }
        }
        return null
    }

    private fun copyJump(template: Any?, preference: Any) {
        val jump = Reflect.readField(template ?: return, "mJumpRes") ?: return
        if (!Reflect.invokeSingleArg(preference, "setJump", jump)) {
            Reflect.writeField(preference, arrayOf("mJumpRes"), jump)
        }
    }

    /** The first sibling row of [cls] on the same screen, used as the layout template for a new row. */
    private fun rootTemplateOf(category: Any, cls: Class<*>): Any? {
        val parent = Reflect.call(category, "getParent") ?: return null
        val siblings = runCatching { PreferenceTree.childrenOf(parent) }.getOrDefault(emptyList())
        return siblings.firstOrNull {
            cls.isInstance(it) && !PreferenceTree.isGroup(it) && keyOf(it) != MelodyPanelGroup.ADVANCED_KEY
        }
    }

    private fun construct(cls: Class<*>, context: Context?): Any? {
        // Host preferences keep a no-arg constructor only in the JVM stand-ins; the real classes need
        // the themed context. Trying the no-arg shape first lets the applier be tested without Android.
        Reflect.newInstanceArgs(cls)?.let { return it }
        if (context == null) return null
        Reflect.newInstanceArgs(cls, Context::class.java to context, AttributeSet::class.java to null)
            ?.let { return it }
        Reflect.newInstanceArgs(
            cls,
            Context::class.java to context,
            AttributeSet::class.java to null,
            Int::class.javaPrimitiveType!! to 0,
        )?.let { return it }
        return Reflect.newInstanceArgs(cls, Context::class.java to context)
    }

    private fun contextOf(preference: Any): Context? =
        Reflect.call(preference, "getContext") as? Context

    // --- row backfill -----------------------------------------------------------------------------

    private fun fillRow(
        preference: Any,
        row: MelodyPanelRow,
        index: Int,
        stateText: MelodyRowStateText,
    ): RowFill {
        // Write only on a real change: the panel re-applies once a second, and an unconditional setter
        // would make the host rebind every row on every tick.
        if (orderOf(preference) != index) setOrder(preference, index)
        val enabled = !row.unavailable
        if (currentEnabled(preference) != enabled) setEnabled(preference, enabled)
        if (!row.kind.interactive && currentSelectable(preference)) setSelectable(preference, false)
        val value = row.state?.let { stateText.text(it) }
        val summary = when (row.kind) {
            MelodyPanelRowKind.SWITCH -> null
            MelodyPanelRowKind.SEGMENTED -> segmentedSummary(row, value)
            MelodyPanelRowKind.SLIDER -> value
            MelodyPanelRowKind.VALUE, MelodyPanelRowKind.PROGRESS -> withUnit(value, row.unit)
            MelodyPanelRowKind.TEXT -> if (row.state != null) value else null
            MelodyPanelRowKind.BUTTON -> null
        }
        var valueChanged = false
        if (row.kind == MelodyPanelRowKind.SEGMENTED) {
            // The native jump layout shows the current value on the right (`setAssignment`), which is
            // where the host's own「降噪效果」row puts it.
            if (summary != null && currentAssignment(preference) != summary) {
                setAssignment(preference, summary)
                valueChanged = true
            }
        } else if (summary != null && currentSummary(preference) != summary) {
            setSummary(preference, summary)
            valueChanged = true
        }
        if (row.kind == MelodyPanelRowKind.SWITCH) {
            val checked = isTruthy(value)
            if (currentChecked(preference) != checked) {
                setChecked(preference, checked)
                valueChanged = true
            }
            // The host setter normally notifies, but the field fallback does not - an explicit nudge
            // keeps the bound RecyclerView row in sync with the value we just wrote (M4.4).
            if (valueChanged) notifyChanged(preference)
            return RowFill(value, summary, checked)
        }
        if (valueChanged) notifyChanged(preference)
        return RowFill(value, summary, null)
    }

    /** The values one [fillRow] call saw, for the deduplicated `melody.panel.group.item` diagnostic. */
    private data class RowFill(val value: String?, val summary: String?, val checked: Boolean?)

    private fun segmentedSummary(row: MelodyPanelRow, value: String?): String? {
        if (value == null) return null
        val index = row.options.indexOf(value)
        if (index < 0) return value
        return row.optionLabels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: value
    }

    private fun withUnit(value: String?, unit: String?): String? {
        if (value == null) return null
        return if (unit.isNullOrBlank()) value else value + unit
    }

    private fun isTruthy(value: String?): Boolean = when (value?.trim()?.lowercase()) {
        "true", "1", "on", "yes" -> true
        else -> false
    }

    // --- reflection primitives -------------------------------------------------------------------

    /**
     * Adds [preference] to [container] and reports whether it actually landed. `addPreference` is
     * R8-renamed (M4.3b D-14), and the "unique single-argument" signature search is ambiguous because
     * `addPreference` / `removePreference` / `onPrepareAddPreference` share a parameter type - so the
     * only reliable test is the effect: call each candidate and watch `childrenOf`.
     */
    private fun addPreference(container: Any, preference: Any): Boolean {
        if (Reflect.invokeSingleArg(container, "addPreference", preference) && contains(container, preference)) {
            return true
        }
        for (owner in Reflect.hierarchyOf(container.javaClass)) {
            for (method in runCatching { owner.declaredMethods }.getOrNull().orEmpty()) {
                if (method.isSynthetic || method.isBridge || Modifier.isStatic(method.modifiers)) continue
                if (method.parameterTypes.size != 1) continue
                if (!method.parameterTypes[0].isAssignableFrom(preference.javaClass)) continue
                if (method.name == "equals") continue
                runCatching {
                    method.isAccessible = true
                    method.invoke(container, preference)
                }
                if (contains(container, preference)) return true
            }
        }
        return false
    }

    private fun contains(container: Any, preference: Any): Boolean =
        runCatching { PreferenceTree.childrenOf(container) }
            .getOrDefault(emptyList())
            .any { child ->
                child === preference ||
                    (keyOf(preference).isNotEmpty() && keyOf(child) == keyOf(preference))
            }

    /** A row we already inserted, verified against the live container before it is reused. */
    private fun findRow(category: Any, key: String): Any? {
        runCatching { PreferenceTree.childrenOf(category) }
            .getOrDefault(emptyList())
            .firstOrNull { keyOf(it) == key }
            ?.let { return it }
        rows[category]?.get(key)?.let { remembered ->
            if (attachedTo(remembered, category)) return remembered
            rows[category]?.remove(key)
        }
        return null
    }

    private fun rememberRow(category: Any, key: String, preference: Any) {
        rows.getOrPut(category) { HashMap() }[key] = preference
    }

    /**
     * `true` when [preference] still sits under [parent]. `getParent` is name-stable in the host's
     * Preference hierarchy; when it is unavailable the caller cannot disprove attachment, so the
     * remembered object is kept (a rebuilt page presents a different parent object anyway).
     */
    private fun attachedTo(preference: Any, parent: Any): Boolean {
        // No `getParent` means we cannot disprove attachment; a rebuilt page presents a new object.
        val method = Reflect.findNoArgMethod(preference.javaClass, "getParent") ?: return true
        val actual = runCatching { method.invoke(preference) }.getOrNull()
        return actual === parent
    }

    /** Only `true` on a positive match; a missing `getParent` must not keep a possibly-stale category. */
    private fun strictlyAttachedTo(preference: Any, parent: Any): Boolean {
        val method = Reflect.findNoArgMethod(preference.javaClass, "getParent") ?: return false
        val actual = runCatching { method.invoke(preference) }.getOrNull()
        return actual === parent
    }

    private fun keyOf(preference: Any): String =
        Reflect.callString(preference, "getKey")?.takeIf { it.isNotBlank() }
            ?: (Reflect.readField(preference, "mKey", "key") as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }
            ?: ""

    private fun orderOf(preference: Any?): Int? =
        Reflect.callInt(preference, "getOrder") ?: (Reflect.readField(preference, "mOrder", "order") as? Int)

    private fun isVisible(preference: Any): Boolean =
        Reflect.callBoolean(preference, "isVisible") ?: (Reflect.readField(preference, "mVisible") as? Boolean) ?: true

    private fun currentTitle(preference: Any): String? =
        Reflect.callCharSequence(preference, "getTitle")?.toString()
            ?: (Reflect.readField(preference, "mTitle", "title") as? CharSequence)?.toString()

    private fun currentSummary(preference: Any): String? =
        Reflect.callCharSequence(preference, "getSummary")?.toString()
            ?: (Reflect.readField(preference, "mSummary", "summary") as? CharSequence)?.toString()

    private fun currentAssignment(preference: Any): String? =
        Reflect.callCharSequence(preference, "getAssignment")?.toString()
            ?: (Reflect.readField(preference, "mAssignment", "assignment") as? CharSequence)?.toString()

    private fun currentEnabled(preference: Any): Boolean =
        Reflect.callBoolean(preference, "isEnabled") ?: (Reflect.readField(preference, "mEnabled") as? Boolean) ?: true

    private fun currentChecked(preference: Any): Boolean =
        Reflect.callBoolean(preference, "isChecked") ?: (Reflect.readField(preference, "mChecked") as? Boolean) ?: false

    private fun currentSelectable(preference: Any): Boolean =
        Reflect.callBoolean(preference, "isSelectable")
            ?: (Reflect.readField(preference, "mSelectable", "selectable") as? Boolean)
            ?: true

    private fun setKey(preference: Any, key: String) {
        if (Reflect.invokeSingleArg(preference, "setKey", key)) return
        Reflect.writeField(preference, arrayOf("mKey", "key"), key)
    }

    private fun setTitle(preference: Any, title: String) {
        if (Reflect.invokeSingleArg(preference, "setTitle", title)) return
        Reflect.writeField(preference, arrayOf("mTitle", "title"), title)
    }

    private fun setSummary(preference: Any, summary: String?) {
        if (summary == null) return
        if (Reflect.invokeSingleArg(preference, "setSummary", summary)) return
        Reflect.writeField(preference, arrayOf("mSummary", "summary"), summary)
    }

    private fun setAssignment(preference: Any, value: String) {
        if (Reflect.invokeSingleArg(preference, "setAssignment", value)) return
        Reflect.writeField(preference, arrayOf("mAssignment", "assignment"), value)
    }

    private fun setOrder(preference: Any, order: Int) {
        if (Reflect.invokeSingleArg(preference, "setOrder", order)) return
        Reflect.writeField(preference, arrayOf("mOrder", "order"), order)
    }

    private fun setEnabled(preference: Any, enabled: Boolean) {
        if (Reflect.invokeSingleArg(preference, "setEnabled", enabled)) return
        Reflect.writeField(preference, arrayOf("mEnabled", "enabled"), enabled)
    }

    private fun setVisible(preference: Any, visible: Boolean) {
        if (Reflect.invokeSingleArg(preference, "setVisible", visible)) return
        Reflect.writeField(preference, arrayOf("mVisible", "visible"), visible)
    }

    private fun setSelectable(preference: Any, selectable: Boolean) {
        if (Reflect.invokeSingleArg(preference, "setSelectable", selectable)) return
        Reflect.writeField(preference, arrayOf("mSelectable", "selectable"), selectable)
    }

    private fun setChecked(preference: Any, checked: Boolean) {
        if (Reflect.invokeSingleArg(preference, "setChecked", checked)) return
        Reflect.writeField(preference, arrayOf("mChecked", "checked"), checked)
    }

    private fun copyLayoutResources(from: Any?, to: Any) {
        if (from == null) return
        val layout = resourceInt(from, "getLayoutResource", "mLayoutResId", "layoutResource") ?: return
        // `0` means "the host's own default"; copying it would blank out the new card's layout.
        if (layout != 0 && !Reflect.invokeSingleArg(to, "setLayoutResource", layout)) {
            Reflect.writeField(to, arrayOf("mLayoutResId", "layoutResource"), layout)
        }
        val widget = resourceInt(from, "getWidgetLayoutResource", "mWidgetLayoutResId", "widgetLayoutResource")
        if (widget != null && widget != 0 && !Reflect.invokeSingleArg(to, "setWidgetLayoutResource", widget)) {
            Reflect.writeField(to, arrayOf("mWidgetLayoutResId", "widgetLayoutResource"), widget)
        }
    }

    private fun resourceInt(preference: Any, getter: String, vararg fields: String): Int? =
        Reflect.callInt(preference, getter) ?: (Reflect.readField(preference, *fields) as? Int)

    private fun notifyChanged(preference: Any) {
        Reflect.call(preference, "notifyChanged")
    }

    // --- logging ----------------------------------------------------------------------------------

    private fun logGroup(screenId: String, mac: String, rows: Int, log: MelodyGroupLog, reason: String? = null) {
        val fingerprint = "$mac|$rows|${reason.orEmpty()}"
        if (lastFingerprint.put(screenId, fingerprint) == fingerprint) return
        if (reason != null) {
            log.event(
                "melody.panel.group",
                listOf("screen" to screenId, "mac" to mac, "key" to MelodyPanelGroup.ADVANCED_KEY, "reason" to reason),
            )
        } else {
            log.event(
                "melody.panel.group",
                listOf("screen" to screenId, "mac" to mac, "key" to MelodyPanelGroup.ADVANCED_KEY, "rows" to rows),
            )
        }
    }

    private fun noteFailure(screenId: String, reason: String, log: MelodyGroupLog, mac: String) {
        if (lastFailure.put(screenId, reason) == reason) return
        log.event(
            "melody.panel.group",
            listOf("screen" to screenId, "mac" to mac, "key" to MelodyPanelGroup.ADVANCED_KEY, "reason" to reason),
        )
    }

    private fun clearFailure(screenId: String) {
        lastFailure.remove(screenId)
    }

    /**
     * One deduplicated line per row, so a logcat capture answers "was the row built, did it land in the
     * category, and what value/summary did it get?" without a second on-device iteration.
     */
    private fun logItem(
        screenId: String,
        mac: String,
        row: MelodyPanelRow,
        preference: Any?,
        built: Boolean,
        added: Boolean,
        filled: RowFill?,
        log: MelodyGroupLog,
    ) {
        val cls = preference?.javaClass?.name ?: "none"
        val fingerprint = listOf(row.kind.wire, cls, built, added, filled?.value, filled?.summary, filled?.checked)
            .joinToString("|")
        if (lastItemFingerprint.put("$screenId|${row.key}", fingerprint) == fingerprint) return
        log.event(
            "melody.panel.group.item",
            listOf(
                "screen" to screenId,
                "mac" to mac,
                "key" to row.key,
                "kind" to row.kind.wire,
                "cls" to cls,
                "built" to built,
                "added" to added,
                "state" to row.state,
                "value" to filled?.value,
                "summary" to filled?.summary,
                "checked" to filled?.checked,
            ),
        )
    }

    private fun logRowFailure(
        screenId: String,
        row: MelodyPanelRow,
        reason: String,
        cls: String?,
        log: MelodyGroupLog,
    ) {
        val fingerprint = listOf(reason, cls).joinToString("|")
        if (lastItemFingerprint.put("$screenId|${row.key}|fail", fingerprint) == fingerprint) return
        log.event(
            "melody.panel.group.item",
            listOf(
                "screen" to screenId,
                "key" to row.key,
                "kind" to row.kind.wire,
                "reason" to reason,
                "cls" to cls,
            ),
        )
    }

    private const val SOUND_GROUP_KEY = "sound"
    private const val ROW_CLASS = "com.coui.appcompat.preference.COUIPreference"
    private const val SWITCH_CLASS = "com.coui.appcompat.preference.COUISwitchPreference"
    private const val JUMP_CLASS = "com.coui.appcompat.preference.COUIJumpPreference"

    /** Bounds for walking up to the screen and scanning the tree for a style donor row. */
    private const val MAX_PARENT_HOPS = 8
    private const val MAX_TREE_SCAN = 300

    /** The category class names survive R8 (M4.3b prereq); the Melody one is the first choice. */
    private val CATEGORY_CLASSES = listOf(
        "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory",
        "com.coui.appcompat.preference.COUIPreferenceCategory",
    )
}

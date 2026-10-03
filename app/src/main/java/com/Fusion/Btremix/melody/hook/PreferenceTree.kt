package com.Fusion.Btremix.melody.hook

import android.app.Activity
import android.os.Bundle
import android.view.View
import com.Fusion.Btremix.melody.api.PanelKeyRow
import java.lang.reflect.Method
import java.util.IdentityHashMap

/**
 * Reflection-only access to the host Preference tree (MELODY_BRIDGE_SPEC §7.1/§7.2).
 *
 * The host bundles `androidx.preference` and COUI with R8-minified names, so neither the fragment
 * base classes nor `Preference`/`PreferenceScreen` may be referenced at compile time. Method names
 * such as `getPreferenceScreen()` / `getPreferenceCount()` are the public API the host itself calls,
 * and every lookup has a structural fallback that works off `getKey`-shaped members or the children
 * list field, so a host update that renames an accessor degrades to a partial dump instead of a crash.
 */
internal object PreferenceTree {

    private const val MAX_DEPTH = 5
    private const val MAX_ROWS = 400

    /** Resolves the `PreferenceScreen` behind a PreferenceFragment, using methods then fields. */
    fun findScreen(host: Any?): Any? {
        if (host == null) return null
        Reflect.call(host, "getPreferenceScreen")?.let { return it }
        Reflect.call(host, "getPreferenceManager")
            ?.let { Reflect.call(it, "getPreferenceScreen") }
            ?.let { return it }
        findScreenInFields(host, 2, IdentityHashMap())?.let { return it }
        return null
    }

    /**
     * Collects every reachable Fragment from an Activity's FragmentManager (one level into child
     * managers, which is where the OneSpace bottom sheet keeps its Preference list). Used by the
     * version-independent lifecycle fallback: when a renamed fragment class cannot be hooked directly
     * the Activity scan still finds its screen.
     */
    fun collectFragments(activity: Activity?): List<Any> {
        if (activity == null) return emptyList()
        val manager = Reflect.call(activity, "getSupportFragmentManager") ?: return emptyList()
        val out = ArrayList<Any>()
        collectFromManager(manager, out, 0)
        return out
    }

    /** Walks a screen/category and returns one row per Preference, depth first. */
    fun dump(screen: Any?, screenId: String): List<PanelKeyRow> {
        if (screen == null) return emptyList()
        val rows = ArrayList<PanelKeyRow>()
        walk(screen, screenId, depth = 0, rows = rows)
        return rows
    }

    private fun walk(container: Any, screenId: String, depth: Int, rows: MutableList<PanelKeyRow>) {
        if (depth >= MAX_DEPTH) return
        for (child in childrenOf(container)) {
            if (rows.size >= MAX_ROWS) return
            rows += describe(child, screenId, depth)
            if (isGroup(child)) walk(child, screenId, depth + 1, rows)
        }
    }

    private fun childrenOf(container: Any): List<Any> {
        val count = Reflect.callInt(container, "getPreferenceCount")
        if (count != null && count > 0) {
            val out = ArrayList<Any>(count)
            for (index in 0 until count) {
                Reflect.callWithInt(container, "getPreference", index)?.let { out += it }
            }
            if (out.isNotEmpty()) return out
        }
        return childrenListOf(container)
    }

    /** Fallback for a stripped `getPreferenceCount`/`getPreference` pair: the children List field. */
    private fun childrenListOf(container: Any): List<Any> {
        for (type in Reflect.hierarchyOf(container.javaClass)) {
            for (field in runCatching { type.declaredFields }.getOrNull().orEmpty()) {
                if (!List::class.java.isAssignableFrom(field.type)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(container) as? List<*>
                }.getOrNull() ?: continue
                if (value.isEmpty()) continue
                if (value.all { looksLikePreference(it) }) return value.filterNotNull()
            }
        }
        return emptyList()
    }

    private fun isGroup(pref: Any): Boolean =
        Reflect.findNoArgMethod(pref.javaClass, "getPreferenceCount") != null

    private fun describe(pref: Any, screenId: String, depth: Int): PanelKeyRow {
        val order = Reflect.callInt(pref, "getOrder") ?: Int.MAX_VALUE
        val key = Reflect.callString(pref, "getKey")
            ?: (Reflect.readField(pref, "mKey", "key") as? CharSequence)?.toString()
        val title = Reflect.callCharSequence(pref, "getTitle")?.toString()
            ?: (Reflect.readField(pref, "mTitle", "title") as? CharSequence)?.toString()
        val visible = Reflect.callBoolean(pref, "isVisible")
            ?: (Reflect.readField(pref, "mVisible", "visible") as? Boolean)
            ?: true
        val enabled = Reflect.callBoolean(pref, "isEnabled")
            ?: (Reflect.readField(pref, "mEnabled", "enabled") as? Boolean)
            ?: true
        return PanelKeyRow(
            screen = screenId,
            key = key?.takeIf { it.isNotBlank() } ?: syntheticKey(pref, order),
            title = title,
            className = pref.javaClass.name,
            visible = visible,
            enabled = enabled,
            order = order,
            depth = depth,
        )
    }

    /**
     * Host rows without a key (separators, headers, some COUI widgets) still have to be identifiable
     * in the dump; class + order is stable for a given panel build.
     */
    private fun syntheticKey(pref: Any, order: Int): String =
        "<" + pref.javaClass.simpleName + "#" + order + ">"

    private fun looksLikePreference(value: Any?): Boolean {
        if (value == null) return false
        return Reflect.findNoArgMethod(value.javaClass, "getKey") != null
            || Reflect.findNoArgMethod(value.javaClass, "getPreferenceCount") != null
    }

    private fun findScreenInFields(root: Any?, maxDepth: Int, seen: IdentityHashMap<Any, Boolean>): Any? {
        if (root == null || maxDepth < 0 || seen.containsKey(root)) return null
        if (isMacScanTerminal(root)) return null
        seen[root] = true

        val fields = Reflect.hierarchyOf(root.javaClass)
            .flatMap { type -> runCatching { type.declaredFields }.getOrNull().orEmpty().asSequence() }
            .mapNotNull { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(root)
                }.getOrNull()
                if (value == null) null else field.type to value
            }
            .toList()

        // A screen-typed field is the exact answer; a group-shaped value is the R8-proof fallback.
        fields.firstOrNull { it.first.name.endsWith("PreferenceScreen") }?.let { return it.second }
        fields.firstOrNull { looksLikeGroup(it.second) }?.let { return it.second }

        if (maxDepth == 0) return null
        // R8 keeps package prefixes but shortens simple names, so recursion is gated on packages.
        for ((type, value) in fields) {
            if (!isHostPackageType(type)) continue
            findScreenInFields(value, maxDepth - 1, seen)?.let { return it }
        }
        return null
    }

    private fun looksLikeGroup(value: Any?): Boolean {
        if (value == null || value is String || value is CharSequence) return false
        // PreferenceGroup (screen/category) is the only androidx-preference type exposing
        // `getPreferenceCount()`; plain preferences never do.
        return Reflect.findNoArgMethod(value.javaClass, "getPreferenceCount") != null
    }

    /** Package prefixes survive R8 (only simple names are shortened), so they are safe recursion gates. */
    private fun isHostPackageType(type: Class<*>): Boolean {
        if (type.isPrimitive || type.isArray) return false
        val name = type.name
        return name.startsWith("com.oplus.") ||
            name.startsWith("com.coui.") ||
            name.startsWith("androidx.preference.")
    }

    private fun collectFromManager(manager: Any?, out: MutableList<Any>, depth: Int) {
        if (manager == null || depth > 2) return
        for (fragment in fragmentsOf(manager)) {
            if (out.none { it === fragment }) out += fragment
            val child = Reflect.call(fragment, "getChildFragmentManager")
            if (child != null && child !== manager) collectFromManager(child, out, depth + 1)
        }
    }

    private fun fragmentsOf(manager: Any): List<Any> {
        val out = ArrayList<Any>()
        appendFragmentList(Reflect.call(manager, "getFragments"), out)
        for (type in Reflect.hierarchyOf(manager.javaClass)) {
            for (field in runCatching { type.declaredFields }.getOrNull().orEmpty()) {
                if (!List::class.java.isAssignableFrom(field.type)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(manager)
                }.getOrNull()
                appendFragmentList(value, out)
            }
        }
        return out
    }

    private fun appendFragmentList(value: Any?, out: MutableList<Any>) {
        val list = value as? List<*> ?: return
        for (item in list) {
            if (item != null && isFragmentLike(item) && out.none { it === item }) out += item
        }
    }

    private fun isFragmentLike(obj: Any): Boolean =
        Reflect.hierarchyOf(obj.javaClass).any { it.name == "androidx.fragment.app.Fragment" }

    private fun isMacScanTerminal(value: Any): Boolean {
        val name = value.javaClass.name
        return name.startsWith("android.view.")
            || name.startsWith("android.widget.")
            || name.startsWith("android.graphics.")
            || name.startsWith("android.content.res.")
            || value is ClassLoader
            || value is Class<*>
    }

    /** `onViewCreated(View, Bundle)` located on the fragment hierarchy (name is preserved as an override). */
    fun findOnViewCreated(cls: Class<*>?): Method? =
        Reflect.findMethod(cls, "onViewCreated", arrayOf(View::class.java, Bundle::class.java))
}

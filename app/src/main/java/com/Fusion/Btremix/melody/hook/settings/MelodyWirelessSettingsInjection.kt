package com.Fusion.Btremix.melody.hook.settings

import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.MelodyAnchorSession
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface

/**
 * "耳机功能" entry on the Bluetooth device detail page of `com.oplus.wirelesssettings`
 * (HANDOFF_MELODY_M3_PLAN.md §4 M3.4 fallback; the settings-side reverse engineering is recorded there).
 *
 * The page (`C2.X` = `DeviceProfilesSettings`, layout `res/xS.xml`) inflates a category
 * `headphone_function_key` holding the jump preference `enter_my_bluetooth_earphone_key`, then decides
 * whether to keep it:
 *
 * - `e3.t.k(name)` (`isOplusWirelessDevices`) must be true, or the device must be a "totally supported
 *   AirPods" device - otherwise the category is removed;
 * - `k()` asks `E2.b.d(name)` (`OplusPodsDataManager.isOplusPodsWhiteSupportDevice`), which walks the
 *   whitelist rows the settings app fetched from Melody `ears_whitelist` and compares names with
 *   `query.equals(row.name)` - an **exact** match.
 *
 * That exact-match is why an injected device never shows up: the whitelist row carries the Definition
 * name ("Sony WF-1000XM3") while the paired device asks with its Bluetooth name ("WF-1000XM3").
 *
 * The hook only widens that comparison for rows that are *already in the fetched whitelist*: the
 * official answer wins, and a row is accepted when one name contains the other. A device whose model is
 * not in the whitelist at all still gets nothing, so the entry can only appear for devices Melody (i.e.
 * BtRemix's injection) claims to support.
 */
internal class MelodyWirelessSettingsInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    fun install() {
        val cls = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.SETTINGS_PODS_DATA_MANAGER, loader)
        if (cls == null) {
            log.event("melody.anchor.missing", "hook" to "settings.pods", "class" to DATA_MANAGER_CLASS)
            return
        }
        val method = Reflect.findMethod(cls, "d", arrayOf(String::class.java))
        if (method == null || method.returnType != Boolean::class.javaPrimitiveType) {
            log.event("melody.anchor.missing", "hook" to "settings.pods", "class" to DATA_MANAGER_CLASS, "method" to "d")
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val official = chain.proceed()
            if (official == true) {
                true
            } else {
                val query = chain.args.getOrNull(0) as? String
                val matched = looseMatch(chain.thisObject, query)
                if (matched != null) {
                    log.event(
                        "melody.inject.settings",
                        "hook" to "isOplusPodsWhiteSupportDevice",
                        "query" to query,
                        "row" to matched,
                    )
                    true
                } else {
                    official
                }
            }
        })
        log.event("melody.anchor.hooked", "hook" to "settings.pods", "class" to cls.name, "method" to method.name)
    }

    /**
     * Returns the whitelist row name that matches [query] loosely, or `null`.
     *
     * The row list is the settings app's own field (`OplusPodsDataManager.a`, a
     * `CopyOnWriteArrayList<E2.a>`); each entry's name is its field `a`. Reading it (instead of keeping
     * our own copy) guarantees the entry can only appear for a device the settings app actually
     * received from Melody.
     */
    private fun looseMatch(manager: Any?, query: String?): String? {
        if (query.isNullOrBlank() || query.length < MIN_NAME_CHARS) return null
        val rows = Reflect.readField(manager, ROWS_FIELD) as? List<*> ?: return null
        for (row in rows) {
            val name = Reflect.readField(row, NAME_FIELD) as? String ?: continue
            if (name.length < MIN_NAME_CHARS) continue
            if (name.equals(query, ignoreCase = true)) return name
            if (name.contains(query, ignoreCase = true)) return name
            if (query.contains(name, ignoreCase = true)) return name
        }
        return null
    }

    private companion object {
        /** `com.oplus.wirelesssettings` `E2.b` = `OplusPodsDataManager` (obfuscated short name). */
        const val DATA_MANAGER_CLASS = "E2.b"
        const val ROWS_FIELD = "a"
        const val NAME_FIELD = "a"

        /** `Buds`, `XM3`, ... are too ambiguous to match loosely. */
        const val MIN_NAME_CHARS = 5
    }
}

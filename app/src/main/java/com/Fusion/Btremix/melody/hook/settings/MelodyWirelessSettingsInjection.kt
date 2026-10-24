package com.Fusion.Btremix.melody.hook.settings

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import com.Fusion.Btremix.melody.hook.MelodyAnchorSession
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.PreferenceTree
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Locale

/**
 * "耳机功能" entry on the Bluetooth device detail page of `com.oplus.wirelesssettings`
 * (HANDOFF_MELODY_M3_PLAN.md §4 M3.4; remade as a runtime COUI row for wirelesssettings 17.5.17).
 *
 * The host layout (`res/xS.xml`) still declares a category `headphone_function_key` and a jump row, but
 * the host decides visibility through its own whitelist copy. We remove that static category and insert
 * an equivalent row only when the paired device is present in the same host-side whitelist.
 *
 * The dynamic row is a real `com.coui.appcompat.preference.COUIJumpPreference` created with the host's
 * themed Preference context. It therefore inherits the same typography, paddings, background grouping
 * and press feedback as every other row on the page. The surrounding category is also a host
 * `COUIPreferenceCategory`; no framework `Preference` substitute is used.
 *
 * The item key is intentionally the stable extension id
 * `com.oplus.wirelesssettings.subsettings.ext`. Its click intent uses Melody's public
 * `MELODY_DISPLAY_EARPHONE` action and the same address/title/device-id extras as the official route.
 */
internal class MelodyWirelessSettingsInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    private var dataManagerClass: Class<*>? = null

    fun install() {
        dataManagerClass = MelodyAnchorSession.classOrNull(
            MelodyAnchorCatalog.SETTINGS_PODS_DATA_MANAGER,
            loader,
        )
        val anchor = MelodyAnchorSession.anchor(MelodyAnchorCatalog.SETTINGS_DEVICE_DETAIL, loader)
        if (anchor == null) {
            log.event(
                "melody.anchor.missing",
                "hook" to "settings.dynamic_entry",
                "class" to DETAIL_CLASS,
                "method" to DETAIL_METHOD,
            )
            return
        }
        val method = anchor.method ?: return
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            runCatching { injectDynamicEntry(chain.thisObject) }
                .onFailure { log.warn("melody.inject.settings.failed", it) }
            result
        })
        log.event(
            "melody.anchor.hooked",
            "hook" to "settings.dynamic_entry",
            "class" to anchor.clazz.name,
            "method" to method.name,
        )
    }

    /**
     * Runs after the host has completed its own resume pass. The host may already have removed the
     * static category; that is expected. We add the dynamic category exactly once when the paired device
     * exists in the host's fetched whitelist, then remove any static category that is still attached.
     */
    private fun injectDynamicEntry(fragment: Any?) {
        val screen = PreferenceTree.findScreen(fragment) ?: return logMissing("screen")
        val staticCategory = Reflect.callSingleArg(fragment, "findPreference", STATIC_CATEGORY_KEY)
            ?: Reflect.readField(fragment, "d0")
        val staticRow = Reflect.callSingleArg(fragment, "findPreference", STATIC_ROW_KEY)
            ?: Reflect.readField(fragment, "e0")

        val existing = findChild(screen, DYNAMIC_CATEGORY_KEY)
        if (existing != null && findChild(existing, DYNAMIC_ITEM_KEY) != null) {
            staticCategory?.let { removePreference(screen, it) }
            return
        }
        removePreference(screen, existing)

        val device = Reflect.readFieldOfType(fragment, BluetoothDevice::class.java) as? BluetoothDevice
            ?: return logMissing("device")
        val name = runCatching { device.name }.getOrNull()?.trim().orEmpty()
        val address = runCatching { device.address }.getOrNull()?.trim().orEmpty()
        if (name.isBlank() || address.isBlank()) return logMissing("identity")

        val matchedRow = supportedWhitelistRow(name) ?: return
        val context = preferenceContext(fragment) ?: return logMissing("context")
        val title = Reflect.callCharSequence(staticRow, "getTitle")
            ?: runCatching {
                val id = context.resources.getIdentifier(
                    HEADPHONE_TITLE_RESOURCE,
                    "string",
                    SETTINGS_PACKAGE,
                )
                if (id != 0) context.getString(id) else null
            }.getOrNull()
            ?: FALLBACK_TITLE

        val category = newNativePreference(CATEGORY_CLASS, context) ?: return logMissing("category")
        val row = newNativePreference(JUMP_CLASS, context) ?: return logMissing("row")
        if (!setKey(category, DYNAMIC_CATEGORY_KEY)) return logMissing("category_key")
        setOrder(category, STATIC_ORDER)
        setPersistent(category, false)
        if (!setKey(row, DYNAMIC_ITEM_KEY)) return logMissing("item_key")
        Reflect.invokeSingleArg(row, "setTitle", title)
        setPersistent(row, false)
        if (!Reflect.invokeSingleArg(row, "setIntent", melodyDetailIntent(name, address))) {
            return logMissing("intent")
        }

        if (!addPreference(category, row) || !addPreference(screen, category)) {
            return logMissing("add")
        }
        staticCategory?.let { removePreference(screen, it) }
        Reflect.call(category, "notifyChanged")
        Reflect.call(row, "notifyChanged")
        log.event(
            "melody.inject.settings.dynamic",
            "key" to DYNAMIC_ITEM_KEY,
            "query" to name,
            "row" to matchedRow,
            "cls" to row.javaClass.name,
        )
    }

    /**
     * The manager's list is already the settings app's fetched `ears_whitelist`. Official exact hits win;
     * the loose fallback only widens within that list, so this cannot invent a supported device.
     */
    private fun supportedWhitelistRow(query: String): String? {
        val manager = dataManager() ?: return null
        if (Reflect.callSingleArg(manager, WHITELIST_METHOD, query) == true) return query
        return looseMatch(manager, query)
    }

    private fun looseMatch(manager: Any?, query: String?): String? {
        if (query.isNullOrBlank()) return null
        val rows = Reflect.readField(manager, ROWS_FIELD) as? List<*> ?: return null
        for (row in rows) {
            val name = Reflect.readField(row, NAME_FIELD) as? String ?: continue
            if (wirelessSettingsNameMatches(query, name)) return name
        }
        return null
    }

    private fun dataManager(): Any? {
        val cls = dataManagerClass ?: return null
        val method = Reflect.findMethod(cls, DATA_MANAGER_FACTORY, emptyArray()) ?: return null
        if (!Modifier.isStatic(method.modifiers)) return null
        return runCatching { method.invoke(null) }.getOrNull()
    }

    private fun preferenceContext(fragment: Any?): Context? {
        return (Reflect.call(fragment, "getPrefContext") ?: Reflect.call(fragment, "getContext")) as? Context
    }

    private fun newNativePreference(className: String, context: Context): Any? {
        val cls = Reflect.loadClass(className, loader) ?: return null
        return Reflect.newInstanceArgs(cls)?.let { return it }
        Reflect.newInstanceArgs(cls, Context::class.java to context)?.let { return it }
        return Reflect.newInstanceArgs(
            cls,
            Context::class.java to context,
            android.util.AttributeSet::class.java to null,
        )
    }

    private fun melodyDetailIntent(name: String, address: String): Intent =
        Intent(ACTION_MELODY_DISPLAY_EARPHONE)
            .setPackage(MELODY_PACKAGE)
            .putExtra(EXTRA_DEVICE_MAC, address)
            .putExtra(EXTRA_DEVICE_TITLE, name)
            .putExtra(EXTRA_DEVICE_ID, melodyDeviceId(address))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun setKey(preference: Any?, key: String): Boolean =
        Reflect.invokeSingleArg(preference, "setKey", key)

    private fun setOrder(preference: Any?, order: Int) {
        Reflect.invokeSingleArg(preference, "setOrder", order)
    }

    private fun setPersistent(preference: Any?, persistent: Boolean) {
        Reflect.invokeSingleArg(preference, "setPersistent", persistent)
    }

    private fun findChild(container: Any, key: String): Any? =
        PreferenceTree.childrenOf(container).firstOrNull { currentKey(it) == key }

    private fun currentKey(preference: Any): String? =
        Reflect.callString(preference, "getKey")
            ?: (Reflect.readField(preference, "mKey", "key") as? CharSequence)?.toString()

    /** Effect-based insertion, matching the renamed `PreferenceGroup.addPreference` in COUI. */
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

    /** Effect-based removal for the same R8 reason; a null target is a successful no-op. */
    private fun removePreference(container: Any, preference: Any?) {
        if (preference == null) return
        if (Reflect.invokeSingleArg(container, "removePreference", preference) && !contains(container, preference)) {
            return
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
                if (!contains(container, preference)) return
            }
        }
    }

    private fun contains(container: Any, preference: Any): Boolean {
        val key = currentKey(preference)
        return PreferenceTree.childrenOf(container).any {
            it === preference || (!key.isNullOrBlank() && currentKey(it) == key)
        }
    }

    private fun logMissing(reason: String) {
        log.event("melody.inject.settings.skip", "reason" to reason)
    }

    private companion object {
        const val DETAIL_CLASS = "com.oplus.wirelesssettings.bluetooth.B"
        const val DETAIL_METHOD = "onResume"

        /** `com.oplus.wirelesssettings` `O3.b` = `OplusPodsDataManager` in 17.5.17. */
        const val DATA_MANAGER_FACTORY = "b"
        const val WHITELIST_METHOD = "c"
        const val ROWS_FIELD = "a"
        const val NAME_FIELD = "a"

        const val STATIC_CATEGORY_KEY = "headphone_function_key"
        const val STATIC_ROW_KEY = "enter_my_bluetooth_earphone_key"
        const val DYNAMIC_CATEGORY_KEY = "com.oplus.wirelesssettings.subsettings.ext.category"
        const val DYNAMIC_ITEM_KEY = "com.oplus.wirelesssettings.subsettings.ext"
        const val CATEGORY_CLASS = "com.coui.appcompat.preference.COUIPreferenceCategory"
        const val JUMP_CLASS = "com.coui.appcompat.preference.COUIJumpPreference"
        const val SETTINGS_PACKAGE = "com.oplus.wirelesssettings"
        const val HEADPHONE_TITLE_RESOURCE = "headphone_function"
        const val FALLBACK_TITLE = "耳机功能"
        const val STATIC_ORDER = 400

        const val MELODY_PACKAGE = "com.oplus.melody"
        const val ACTION_MELODY_DISPLAY_EARPHONE = "oplus.intent.action.MELODY_DISPLAY_EARPHONE"
        const val EXTRA_DEVICE_MAC = "device_mac_info"
        const val EXTRA_DEVICE_TITLE = "device_title"
        const val EXTRA_DEVICE_ID = "device_id"
    }
}

/** Short generic suffixes are too ambiguous to match loosely. */
internal fun wirelessSettingsNameMatches(query: String?, name: String?): Boolean {
    if (query == null || name == null || query.length < 5 || name.length < 5) return false
    return name.equals(query, ignoreCase = true) ||
        name.contains(query, ignoreCase = true) ||
        query.contains(name, ignoreCase = true)
}

/** Melody's detail deep link uses the same address digest as the official wirelesssettings row. */
internal fun melodyDeviceId(address: String): String = runCatching {
    MessageDigest.getInstance("MD5")
        .digest(address.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.US, it) }
}.getOrDefault("")

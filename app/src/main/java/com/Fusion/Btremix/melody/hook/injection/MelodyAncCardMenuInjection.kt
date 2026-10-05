package com.Fusion.Btremix.melody.hook.injection

import android.app.Application
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * M5.1 follow-up: build the device-centre card's noise menu from our projection.
 *
 * The card's rows are not taken from the `EarphoneDTO`: `i9/c.e(app, sqlId, sdkDeviceInfo,
 * CurrentNoiseModeInfo, modes, res)` computes the checked entry from
 * `CurrentNoiseModeInfo.getCurrentNoiseReductionModeIndex()`, maps it back to a `modeType` through the
 * mode table and hands `(modeType, supportedModes, order)` to `i9/c.a` / `i9/c.b`
 * (`docs/melody-capability-map.md` §8.1). That value comes from the host's own btsdk session - which M3.4
 * suppresses - so it is `-1`, and `i9/c.e` falls back to `modeType = 1` (`Ba/r.b` = "关闭"). That is
 * exactly the "device card shows 关闭" report.
 *
 * [MelodyAncNoiseInfoInjection] answers the getter itself; this hook is the second, MAC-correct layer for
 * the same problem: when the card row is rebuilt we re-dispatch the host's own builder with a
 * `CurrentNoiseModeInfo` whose index is the Definition's projection for that row's MAC. Re-dispatching
 * (rather than rewriting the argument in place) keeps the host's semantics exactly - the entries are
 * still built by `i9/c.a` / `i9/c.b`, only the "current mode" input changes.
 *
 * Fail-open: unmanaged MAC, no bridge, no projection, a missing anchor or an unbuildable value object all
 * fall back to `chain.proceed()`.
 */
internal class MelodyAncCardMenuInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    /** Guards the reflection re-dispatch so the hooked method is not re-entered. */
    private val redispatching = ThreadLocal<Boolean>()

    /** Last `(mac, our index)` logged, so repeated card rebuilds do not flood logcat. */
    private val lastLogged = ConcurrentHashMap<String, Int>()

    /** Last fail-open reason per MAC (see [noteSkip]). */
    private val lastSkip = ConcurrentHashMap<String, String>()

    @Volatile
    private var noiseClass: Class<*>? = null

    /** The host application, captured from `Application.onCreate` (same anchor the bridge uses). */
    @Volatile
    private var application: Application? = null

    fun install() {
        hookApplication()
        val cls = Reflect.loadClass(UTILS_CLASS, loader)
        if (cls == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "class" to UTILS_CLASS)
            return
        }
        noiseClass = Reflect.loadClass(NOISE_CLASS, loader)
        val method = findBuilder(cls)
        if (method == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "class" to cls.name, "target" to METHOD)
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            if (redispatching.get() == true) {
                chain.proceed()
            } else {
                runCatching { fix(chain.args, method) }.getOrNull() ?: chain.proceed()
            }
        })
        log.event("melody.anchor.hooked", "hook" to HOOK, "class" to cls.name, "method" to method.name)
    }

    private fun hookApplication() {
        val onCreate = runCatching { Application::class.java.getMethod("onCreate") }.getOrNull() ?: return
        module.hook(onCreate).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            runCatching {
                val app = chain.thisObject as? Application
                if (app != null && application == null) {
                    application = app
                    log.event("melody.anchor.hooked", "hook" to HOOK, "target" to "application", "class" to app.javaClass.name)
                }
            }
            result
        })
    }

    /**
     * Rebuilds the device-centre row for [mac] with our projected noise index and republishes it.
     *
     * The row lives in the SDK repository (`device_info_table_new.device_data`), not in the live
     * `EarphoneDTO`, and the host only fills it from its own btsdk session - which M3.4 suppresses. Its
     * own restore path even builds the menus from an **empty** `CurrentNoiseModeInfo` (index `-1`), which
     * `i9/c.e` renders as "关闭". So after a redirected write we replay that path:
     * `DeviceInfoManager.d(mac)` -> `i9/c.d(app, row)` (our `e` hook substitutes the projected index) ->
     * `DeviceInfoManager.h(row)` (update + notify the card).
     *
     * Fail-open: any missing anchor or null row simply returns.
     */
    fun republish(mac: String) {
        val app = application ?: return noteSkip(mac, "app")
        val row = runCatching { findRow(mac) }.getOrNull() ?: return noteSkip(mac, "row")
        val restore = runCatching { restoreMethod(row) }.getOrNull() ?: return noteSkip(mac, "restore")
        // `i9/c.d` is `void`: `invoke` returns null even on success, so the result must be judged by
        // whether the call threw - a `getOrNull() ?: return` here silently skipped the publish step.
        val restored = runCatching {
            restore.isAccessible = true
            restore.invoke(null, app, row)
        }.isSuccess
        if (!restored) return noteSkip(mac, "invoke")
        val published = runCatching { publish(row) }.isSuccess
        log.event("melody.anc.card.publish", "mac" to mac, "updated" to published)
    }

    /** One line per MAC and reason, so a silent fail-open is distinguishable from "never called". */
    private fun noteSkip(mac: String, reason: String) {
        if (lastSkip.put(mac, reason) == reason) return
        log.event("melody.anc.card.skip", "mac" to mac, "reason" to reason)
    }

    private fun findRow(mac: String): Any? {
        val manager = Reflect.loadClass(SDK_MANAGER, loader) ?: return null
        val find = Reflect.findMethod(manager, "d", arrayOf(String::class.java)) ?: return null
        find.isAccessible = true
        return find.invoke(null, mac)
    }

    /** `i9.c.d(MelodyApplication, sdk.DeviceInfo)` - the host's own "restore the card's noise menus". */
    private fun restoreMethod(row: Any): Method? =
        runCatching { Reflect.loadClass(UTILS_CLASS, loader) }.getOrNull()
            ?.let { utils ->
                runCatching { utils.declaredMethods }.getOrNull().orEmpty().firstOrNull { method ->
                    method.name == RESTORE &&
                        !method.isSynthetic &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[1] == row.javaClass
                }
            }

    /** `DeviceInfoManager.h(DeviceInfo)` - SDK update + card notification. */
    private fun publish(row: Any): Boolean {
        val manager = Reflect.loadClass(SDK_MANAGER, loader) ?: return false
        val update = Reflect.findMethod(manager, "h", arrayOf(row.javaClass)) ?: return false
        update.isAccessible = true
        update.invoke(null, row)
        return true
    }

    /**
     * The builder is `e(MelodyApplication, String, sdk.DeviceInfo, CurrentNoiseModeInfo, List, int)`.
     * It is resolved by shape (arity + the `CurrentNoiseModeInfo` parameter) so the SDK/application types
     * do not have to be loaded by name.
     */
    private fun findBuilder(cls: Class<*>): Method? {
        val noise = noiseClass ?: return null
        return runCatching { cls.declaredMethods }.getOrNull().orEmpty().firstOrNull { method ->
            method.name == METHOD &&
                !method.isSynthetic &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == PARAMS &&
                method.parameterTypes[3] == noise
        }?.also { runCatching { it.isAccessible = true } }
    }

    /** Re-dispatches the builder with a noise value carrying our index, or `null` to proceed. */
    private fun fix(args: List<Any?>, method: Method): Any? {
        if (args.size != PARAMS) return null
        val mac = Reflect.callString(args[2], "getMacAddress")?.let(MelodyMac::normalize) ?: return null
        val client = MelodyBridgeClients.existing() ?: return null
        val managed = runCatching { client.managedMacsFast() }.getOrDefault(emptyList())
        if (managed.none { MelodyMac.normalize(it) == mac }) return null
        val index = runCatching { client.currentAncIndexFast(mac) }.getOrNull() ?: return null
        val value = buildNoiseValue(index) ?: return null
        note(mac, index, Reflect.callInt(args[3], "getCurrentNoiseReductionModeIndex"))
        val replaced = args.toMutableList().also { it[3] = value }
        redispatching.set(true)
        try {
            method.invoke(null, *replaced.toTypedArray())
        } finally {
            redispatching.set(false)
        }
        return REDISPATCHED
    }

    /**
     * A fresh value object whose `mOpenNoiseReductionMode` has only [index] set, so the host's own
     * `getCurrentNoiseReductionModeIndex()` answers exactly that. Building a fresh one (instead of copying
     * the host's) also guarantees no lower, stale bit survives - the getter returns the *first* open bit.
     */
    private fun buildNoiseValue(index: Int): Any? {
        val cls = noiseClass ?: return null
        val instance = Reflect.newInstanceArgs(cls) ?: return null
        val setter = Reflect.findMethod(instance.javaClass, SET_VALUE, arrayOf(Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!))
            ?: return null
        return runCatching {
            setter.isAccessible = true
            setter.invoke(instance, index, true)
        }.getOrNull()?.let { instance }
    }

    private fun note(mac: String, index: Int, official: Int?) {
        if (lastLogged.put(mac, index) == index) return
        log.event("melody.anc.card", "mac" to mac, "index" to index, "official" to official)
    }

    private companion object {
        const val HOOK = "inject.anc_card"
        const val UTILS_CLASS = "i9.c"
        const val RESTORE = "d"
        const val NOISE_CLASS = "com.oplus.melody.btsdk.api.data.CurrentNoiseModeInfo"
        const val SDK_MANAGER = "com.oplus.mydevices.sdk.DeviceInfoManager"
        const val METHOD = "e"
        const val PARAMS = 6
        const val SET_VALUE = "setCurrentNoiseReductionModeValue"

        /** Marker telling the hook the re-dispatch already ran (the method is `void`, so any non-null works). */
        val REDISPATCHED = Any()
    }
}

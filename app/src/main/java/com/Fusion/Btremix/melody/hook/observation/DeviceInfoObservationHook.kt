package com.Fusion.Btremix.melody.hook.observation

import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import com.Fusion.Btremix.melody.api.ObservationRateLimiter
import com.Fusion.Btremix.melody.hook.MelodyAnchorSession
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface

/**
 * M1 read-only observation of the device registry (MELODY_BRIDGE_SPEC §5.2, §12 M1).
 *
 * `DeviceInfoManager` decides whether the host is willing to build a control channel for a
 * Bluetooth address: `f(...)` reads `SupportDeviceConfig.isSupportSpp()` before creating a
 * `DeviceInfo`, `h/d` look the registry up, and `c/e/j` push state into it. Observing these calls
 * shows exactly when and with which arguments Melody enters (or abandons) the connect path — the
 * reference behaviour the M3 support injection and transport suppression have to match.
 *
 * The class name is R8-preserved, but its methods are single letters and can shift between host
 * releases, so every anchor is matched by name + parameter types with a unique param-shape fallback
 * (the same policy the analysis report prescribes in §10).
 */
internal class DeviceInfoObservationHook(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    /**
     * One anchored entry point. [polling] marks the registry lookups the host repeats for every
     * bonded device on every refresh: on a real device those fire hundreds of times per second, and
     * an unthrottled log stream rate-limits logd so badly that unrelated diagnostics are dropped.
     */
    private data class Anchor(
        val name: String,
        val params: Array<Class<*>>,
        val label: String,
        val polling: Boolean = false,
    )

    private val limiter = ObservationRateLimiter(POLLING_LOG_INTERVAL_MS)

    fun install() {
        val managerClass = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.BTSDK_DEVICE_INFO_MANAGER, loader)
        if (managerClass == null) {
            log.event("melody.anchor.missing", "hook" to "deviceinfo", "class" to MANAGER_CLASS)
            return
        }
        val deviceInfoClass = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.BTSDK_DEVICE_INFO, loader)
        if (deviceInfoClass == null) {
            log.event("melody.anchor.missing", "hook" to "deviceinfo", "class" to DEVICE_INFO_CLASS)
        }
        for (anchor in anchors(deviceInfoClass)) hookAnchor(managerClass, anchor)
    }

    private fun anchors(deviceInfoClass: Class<*>?): List<Anchor> = listOfNotNull(
        Anchor("f", arrayOf(Int::class.javaPrimitiveType!!, BluetoothDevice::class.java, String::class.java, String::class.java), "create"),
        Anchor("h", arrayOf(String::class.java), "findByAddress", polling = true),
        Anchor("d", arrayOf(BluetoothDevice::class.java), "checkGet", polling = true),
        Anchor("i", arrayOf(BluetoothDevice::class.java), "getOrGenerate", polling = true),
        Anchor("a", arrayOf(Int::class.javaPrimitiveType!!, BluetoothDevice::class.java), "addWithProductId"),
        Anchor("b", arrayOf(Int::class.javaPrimitiveType!!, String::class.java), "addAddressWithProductId"),
        Anchor("j", arrayOf(Int::class.javaPrimitiveType!!, String::class.java), "setProductId"),
        deviceInfoClass?.let { Anchor("c", arrayOf(it), "updateStateC") },
        deviceInfoClass?.let { Anchor("e", arrayOf(it), "updateStateE") },
    )

    private fun hookAnchor(managerClass: Class<*>, anchor: Anchor) {
        val method = Reflect.findMethod(managerClass, anchor.name, anchor.params)
            ?: Reflect.findUniqueMethodByParams(managerClass, anchor.params)
        if (method == null) {
            log.event(
                "melody.anchor.missing",
                "hook" to "deviceinfo",
                "target" to anchor.label,
                "name" to anchor.name,
                "arity" to anchor.params.size,
            )
            return
        }
        if (method.name != anchor.name) {
            log.event("melody.anchor.renamed", "target" to anchor.label, "expected" to anchor.name, "resolved" to method.name)
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val args = runCatching { describeArgs(chain.args) }.getOrNull()
            var result: Any? = null
            var failure: Throwable? = null
            try {
                result = chain.proceed()
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                // Single combined event: the polling lookups run for every bonded device, so a
                // separate call/result pair per invocation would double an already huge volume.
                runCatching {
                    val key = anchor.label + '|' + args
                    val suppressed = limiter.allow(key, SystemClock.elapsedRealtime(), anchor.polling)
                    if (suppressed != null) {
                        log.event(
                            "melody.deviceinfo",
                            "target" to anchor.label,
                            "method" to method.name,
                            "args" to args,
                            "result" to (failure?.let { "threw:" + it.javaClass.simpleName } ?: describeResult(result)),
                            "suppressed" to suppressed,
                        )
                    }
                }
            }
            result
        })
        log.event("melody.anchor.hooked", "hook" to "deviceinfo", "target" to anchor.label, "method" to method.name)
    }

    private fun describeArgs(args: List<Any?>): String = args.joinToString(",") { describeValue(it) }

    private fun describeValue(value: Any?): String = when (value) {
        null -> "null"
        is BluetoothDevice -> value.address ?: "<no-address>"
        is String -> MelodyLog.sanitize(value, MAX_CELL_CHARS)
        is Int, is Long, is Boolean -> value.toString()
        else -> value.javaClass.simpleName
    }

    /**
     * Summarises a returned `DeviceInfo` through the getters the analysis report lists as
     * R8-preserved (Parcelable/Kotlin data class accessors). Every lookup is optional: an unknown
     * shape simply yields a shorter summary.
     */
    private fun describeResult(result: Any?): String? {
        if (result == null) return "null"
        val parts = ArrayList<String>(8)
        Reflect.callString(result, "getDeviceAddress")?.let { parts += "addr=" + MelodyLog.formatValue(it) }
        Reflect.call(result, "getProductId")?.let { parts += "productId=$it" }
        Reflect.callString(result, "getDeviceName")?.let { parts += "name=" + MelodyLog.formatValue(it) }
        Reflect.callInt(result, "getDeviceA2dpConnectState")?.let { parts += "a2dp=$it" }
        Reflect.callInt(result, "getDeviceHeadsetConnectState")?.let { parts += "hfp=$it" }
        Reflect.callInt(result, "getDeviceAclConnectState")?.let { parts += "acl=$it" }
        Reflect.callBoolean(result, "isConnected")?.let { parts += "connected=$it" }
        Reflect.callInt(result, "getSppOverGattConnectionState")?.let { parts += "spp=$it" }
        if (parts.isEmpty()) return result.javaClass.name
        return parts.joinToString(",")
    }

    private companion object {
        const val MANAGER_CLASS = "com.oplus.melody.btsdk.api.manager.DeviceInfoManager"
        const val DEVICE_INFO_CLASS = "com.oplus.melody.btsdk.api.data.DeviceInfo"
        const val MAX_CELL_CHARS = 120

        /** Registry lookups repeat per bonded device; 10 s keeps connect sequences readable. */
        const val POLLING_LOG_INTERVAL_MS = 10_000L
    }
}

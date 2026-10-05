package com.fusion.melodyLinkNeo.melody.hook.observation

import android.content.ContentProvider
import android.os.Bundle
import com.fusion.melodyLinkNeo.melody.hook.MelodyAnchorSession
import com.fusion.melodyLinkNeo.melody.hook.MelodyLog
import com.fusion.melodyLinkNeo.melody.hook.Reflect
import com.fusion.melodyLinkNeo.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface

/**
 * M3.4b read-only observation of the device-centre card protocol
 * (`HANDOFF_MELODY_M3_PLAN.md` §4 M3.4 fallback, `COLOROS_MELODY_ANALYSIS.md` §I1).
 *
 * The M3.4 device run showed the whitelist and registry injection working (`find_whitelist` answers,
 * `h(mac)` returns our synthesised `DeviceInfo`) while the device centre still showed no entry — and,
 * crucially, the device centre never asked `MelodyAliveProvider` about the headset at all. It talks to
 * `MyDeviceProvider` (a `com.oplus.mydevices.sdk.DeviceAppProvider`) instead, which M1 never observed.
 *
 * This hook exists to capture that protocol before writing any synthesis against it: what the device
 * centre asks for (method + bundle), what the host answers (bundle keys/values), and which methods the
 * provider actually exposes. Once captured, the entry can be synthesised the same way the whitelist
 * was - or the observation proves the card comes from somewhere else entirely.
 */
internal class DeviceCardObservationHook(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    fun install() {
        val cls = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.PROVIDER_MY_DEVICE, loader)
        if (cls == null) {
            log.event("melody.anchor.missing", "hook" to "devicecard", "class" to PROVIDER_CLASS)
            return
        }
        describeSurface(cls)
        hookCall(cls)
        hookCallWithAuthority(cls)
        hookQuery(cls)
    }

    /** The declared method set answers "which protocol does this provider speak" without a dex dump. */
    private fun describeSurface(cls: Class<*>) {
        runCatching {
            val names = cls.declaredMethods
                .filterNot { it.isSynthetic || it.isBridge }
                .map { method -> method.name + '/' + method.parameterTypes.size }
                .distinct()
                .sorted()
            log.event(
                "melody.devicecard.surface",
                "class" to cls.name,
                "methods" to MelodyLog.sanitize(names.joinToString(","), MAX_SURFACE_CHARS),
            )
        }.onFailure { log.warn("melody.devicecard.surface_failed", it) }
    }

    private fun hookCall(cls: Class<*>) {
        val method = Reflect.findMethod(
            cls,
            "call",
            arrayOf(String::class.java, String::class.java, Bundle::class.java),
        ) ?: run {
            // The SDK base class declares `call`, so a missing override is expected on some builds.
            log.event("melody.anchor.missing", "hook" to "devicecard.call", "class" to cls.name)
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val methodName = chain.args.getOrNull(0)
            val arg = chain.args.getOrNull(1)
            val extras = chain.args.getOrNull(2) as? Bundle
            runCatching {
                log.event(
                    "melody.devicecard.call",
                    "caller" to runCatching { (chain.thisObject as? ContentProvider)?.callingPackage }.getOrNull(),
                    "method" to methodName,
                    "arg" to arg,
                    "extras" to describeBundle(extras),
                )
            }
            val result = chain.proceed()
            runCatching {
                log.event(
                    "melody.devicecard.call.done",
                    "method" to methodName,
                    "result" to describeResult(result),
                )
            }
            result
        })
        log.event("melody.anchor.hooked", "hook" to "devicecard.call", "class" to cls.name)
    }

    /**
     * `query` is the other half of the SDK contract (the card list the device centre reads before it
     * asks anything): only its shape is interesting, and only when it answers about a bonded device.
     */
    private fun hookQuery(cls: Class<*>) {
        val stringArray = emptyArray<String>().javaClass
        val method = Reflect.findMethod(
            cls,
            "query",
            arrayOf(android.net.Uri::class.java, stringArray, String::class.java, stringArray, String::class.java),
        ) ?: Reflect.findUniqueMethodByParams(
            cls,
            arrayOf(android.net.Uri::class.java, stringArray, Bundle::class.java, android.os.CancellationSignal::class.java),
        ) ?: return
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val uri = chain.args.getOrNull(0)
            val selection = chain.args.getOrNull(2)
            val selectionArgs = (chain.args.getOrNull(3) as? Array<*>)
                ?.mapNotNull { it as? String }
                ?.joinToString(",")
            val result = chain.proceed()
            runCatching {
                log.event(
                    "melody.devicecard.query",
                    "caller" to runCatching { (chain.thisObject as? ContentProvider)?.callingPackage }.getOrNull(),
                    "uri" to uri?.toString()?.let { MelodyLog.sanitize(it, MAX_CELL_CHARS) },
                    "selection" to selection,
                    "args" to selectionArgs,
                    "result" to describeCursor(result),
                )
            }
            result
        })
        log.event("melody.anchor.hooked", "hook" to "devicecard.query", "class" to cls.name)
    }

    /**
     * `ContentProvider.call(String authority, String method, String arg, Bundle extras)` - the variant
     * the device centre / Bluetooth settings actually invoke. The 3-argument declaration above exists on
     * the SDK base class but is never the one called, which is why the first observation round only saw
     * `query`.
     */
    private fun hookCallWithAuthority(cls: Class<*>) {
        val params = arrayOf(String::class.java, String::class.java, String::class.java, Bundle::class.java)
        val method = Reflect.findMethod(cls, "call", params) ?: run {
            log.event("melody.anchor.missing", "hook" to "devicecard.call4", "class" to cls.name)
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val methodName = chain.args.getOrNull(1)
            val arg = chain.args.getOrNull(2)
            val extras = chain.args.getOrNull(3) as? Bundle
            runCatching {
                log.event(
                    "melody.devicecard.call4",
                    "caller" to runCatching { (chain.thisObject as? ContentProvider)?.callingPackage }.getOrNull(),
                    "authority" to chain.args.getOrNull(0),
                    "method" to methodName,
                    "arg" to arg,
                    "extras" to describeBundle(extras),
                )
            }
            val result = chain.proceed()
            runCatching {
                log.event(
                    "melody.devicecard.call4.done",
                    "method" to methodName,
                    "arg" to arg,
                    "result" to describeResult(result),
                )
            }
            result
        })
        log.event("melody.anchor.hooked", "hook" to "devicecard.call4", "class" to cls.name)
    }

    @Suppress("DEPRECATION") // the typed Bundle getters cannot dump an unknown protocol
    private fun describeBundle(bundle: Bundle?): String? {
        if (bundle == null) return null
        return runCatching {
            bundle.keySet().take(MAX_KEYS).joinToString(";") { key ->
                key + "=" + describeValue(bundle.get(key))
            }
        }.getOrNull()
    }

    private fun describeResult(result: Any?): String? = when (result) {
        null -> "null"
        is Bundle -> "bundle{" + describeBundle(result) + "}"
        is android.database.Cursor -> describeCursor(result)
        else -> MelodyLog.sanitize(result.toString(), MAX_CELL_CHARS)
    }

    private fun describeCursor(cursor: Any?): String? = runCatching {
        val typed = cursor as? android.database.Cursor ?: return@runCatching null
        val columns = typed.columnNames?.joinToString(",").orEmpty()
        val count = typed.count
        val first = if (count > 0 && typed.moveToFirst()) {
            typed.columnNames.joinToString(";") { name ->
                name + "=" + describeValue(typed.getString(typed.getColumnIndex(name)))
            }
        } else {
            ""
        }
        "count=$count columns=[$columns] row0{$first}"
    }.getOrNull()

    private fun describeValue(value: Any?): String = when (value) {
        null -> "null"
        is ByteArray -> "bytes(" + value.size + ")"
        is Array<*> -> value.joinToString(",", "[", "]") { describeValue(it) }
        else -> MelodyLog.sanitize(value.toString(), MAX_CELL_CHARS)
    }

    private companion object {
        const val PROVIDER_CLASS = "com.oplus.melody.mydevices.devicecard.MyDeviceProvider"
        const val MAX_KEYS = 12
        const val MAX_CELL_CHARS = 160
        const val MAX_SURFACE_CHARS = 900
    }
}

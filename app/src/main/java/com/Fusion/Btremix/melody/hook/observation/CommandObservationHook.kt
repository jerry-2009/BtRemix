package com.Fusion.Btremix.melody.hook.observation

import android.content.ContentProvider
import android.os.Bundle
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * M1 read-only observation of the device-centre SDK entrance (MELODY_BRIDGE_SPEC §6.2, §12 M1).
 *
 * `EarphoneControlProvider.call` serves the `melody_method_*` protocol used by the device centre SDK.
 *
 * The `setgate` broadcast observation moved to the M5.2 redirect hook
 * ([com.Fusion.Btremix.melody.hook.injection.MelodySetgateRedirectInjection]): one method must have one
 * hook so a take-over cannot swallow the `melody.command.receive` control line. This class keeps the
 * provider hook read-only for now; M5.3 upgrades it in the same way.
 *
 * The name is a framework override, hence R8 keeps it.
 */
internal class CommandObservationHook(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    fun install() {
        hookEarphoneControlProvider()
    }

    private fun hookEarphoneControlProvider() {
        val cls = Reflect.loadClass(EARPHONE_CONTROL_CLASS, loader)
        if (cls == null) {
            log.event("melody.anchor.missing", "hook" to "earphone.call", "class" to EARPHONE_CONTROL_CLASS)
            return
        }
        val method = Reflect.findMethod(
            cls,
            "call",
            arrayOf(String::class.java, String::class.java, Bundle::class.java),
        )
        if (method == null) {
            log.event("melody.anchor.missing", "hook" to "earphone.call", "class" to EARPHONE_CONTROL_CLASS)
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val methodName = chain.args.getOrNull(0)
            val arg = chain.args.getOrNull(1)
            val extras = chain.args.getOrNull(2) as? Bundle
            runCatching {
                log.event(
                    "melody.command.call",
                    "caller" to runCatching { (chain.thisObject as? ContentProvider)?.callingPackage }.getOrNull(),
                    "method" to methodName,
                    "arg" to arg,
                    "extras" to describeBundle(extras),
                )
            }
            val result = chain.proceed()
            runCatching {
                when (result) {
                    null -> log.event("melody.command.call.done", "method" to methodName, "result" to "null")
                    is Bundle -> log.event(
                        "melody.command.call.done",
                        "method" to methodName,
                        "result" to "bundle",
                        "keys" to result.keySet().toList(),
                    )
                    else -> log.event("melody.command.call.done", "method" to methodName, "result" to result.toString())
                }
            }
            result
        })
        log.event("melody.anchor.hooked", "hook" to "earphone.call", "class" to cls.name)
    }

    @Suppress("DEPRECATION") // generic value dump: the typed Bundle getters do not help here
    private fun describeBundle(bundle: Bundle?): String? {
        if (bundle == null) return null
        return runCatching {
            bundle.keySet().take(MAX_EXTRAS).joinToString(";") { key ->
                key + "=" + describeValue(bundle.get(key))
            }
        }.getOrNull()
    }

    private fun describeValue(value: Any?): String = when (value) {
        null -> "null"
        is Array<*> -> value.joinToString(",", "[", "]") { describeValue(it) }
        else -> MelodyLog.sanitize(value.toString(), MAX_CELL_CHARS)
    }

    private companion object {
        const val EARPHONE_CONTROL_CLASS = "com.oplus.melody.provider.EarphoneControlProvider"
        const val MAX_EXTRAS = 10
        const val MAX_CELL_CHARS = 160
    }
}

package com.Fusion.Btremix.melody.hook.observation

import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * M1 read-only observation of the two system-level control entrances (MELODY_BRIDGE_SPEC §6.2, §12 M1).
 *
 * - `NoiseReductionCommand.onReceive` handles the exported `com.oplus.melody.setgate` broadcast used
 *   by the device card / settings;
 * - `EarphoneControlProvider.call` serves the `melody_method_*` protocol used by the device centre SDK.
 *
 * M5 redirects both to BtRemix; M1 only records who sends what (sender uid resolved to packages,
 * extras, method codes) so the redirect can be written against observed traffic instead of guesses.
 * Both names are framework overrides, hence R8 keeps them.
 */
internal class CommandObservationHook(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    fun install() {
        hookNoiseReductionCommand()
        hookEarphoneControlProvider()
    }

    private fun hookNoiseReductionCommand() {
        val cls = Reflect.loadClass(NOISE_COMMAND_CLASS, loader)
        if (cls == null) {
            log.event("melody.anchor.missing", "hook" to "anc.onReceive", "class" to NOISE_COMMAND_CLASS)
            return
        }
        val method = Reflect.findMethod(cls, "onReceive", arrayOf(Context::class.java, Intent::class.java))
        if (method == null) {
            log.event("melody.anchor.missing", "hook" to "anc.onReceive", "class" to NOISE_COMMAND_CLASS)
            return
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val intent = chain.args.getOrNull(1) as? Intent
            runCatching {
                log.event(
                    "melody.command.receive",
                    "sender" to describeUid(chain.args.getOrNull(0) as? Context),
                    "action" to intent?.action,
                    "extras" to describeIntent(intent),
                )
            }
            val result = chain.proceed()
            runCatching { log.event("melody.command.receive.done", "action" to intent?.action) }
            result
        })
        log.event("melody.anchor.hooked", "hook" to "anc.onReceive", "class" to cls.name)
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

    private fun describeUid(context: Context?): String {
        val uid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
        val packages = runCatching {
            context?.packageManager?.getPackagesForUid(uid)?.joinToString(",")
        }.getOrNull()
        return if (packages.isNullOrBlank()) "uid=$uid" else "uid=$uid pkg=$packages"
    }

    @Suppress("DEPRECATION") // generic value dump: the typed Bundle getters do not help here
    private fun describeIntent(intent: Intent?): String? {
        if (intent == null) return null
        return runCatching {
            val builder = StringBuilder()
            intent.extras?.keySet()?.take(MAX_EXTRAS)?.forEach { key ->
                builder.append(key).append('=').append(describeValue(intent.extras?.get(key))).append(';')
            }
            if (intent.dataString != null) builder.append("data=").append(intent.dataString)
            builder.toString()
        }.getOrNull()
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
        const val NOISE_COMMAND_CLASS =
            "com.oplus.melody.mydevices.devicecard.noisereduction.NoiseReductionCommand"
        const val EARPHONE_CONTROL_CLASS = "com.oplus.melody.provider.EarphoneControlProvider"
        const val MAX_EXTRAS = 10
        const val MAX_CELL_CHARS = 160
    }
}

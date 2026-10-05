package com.Fusion.Btremix.melody.hook.injection

import android.content.ContentProvider
import android.os.Bundle
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.melody.api.MelodyAncPolicy
import com.Fusion.Btremix.melody.api.MelodyAncRedirectPolicy
import com.Fusion.Btremix.melody.api.MelodyBridgeResult
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.api.MelodyProviderCallPolicy
import com.Fusion.Btremix.melody.hook.MelodyAnchorResolver
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * M5.3: takes over the device-centre SDK lane (`HANDOFF_MELODY_M5_PLAN.md` §4 M5.3,
 * `docs/melody-capability-map.md` §8.6).
 *
 * `com.oplus.melody.provider.EarphoneControlProvider.call(method, arg, extras)` is the
 * `melody_method_*` protocol the device centre SDK (and the system UI behind it) drives the headset
 * with. Only `melody_method_noise_reduction` writes ANC: it maps `extras.type` (the host `modeType`)
 * through the injected table and then writes through the collection point M5.1 already owns. Hooking
 * `call` is the third layer - it skips even the host's own active-earphone / wear validation, keeps
 * working if the `v0` anchor is renamed, and returns exactly what the original caller consumed
 * (`null`, see D-19 as corrected in the M5.0 freeze: every branch ends in `super.call`, whose default
 * is `null`).
 *
 * The M1 read-only `melody.command.call/.done` observation is folded into this same hook (one hook per
 * method, so a take-over can never swallow the control line), which is why
 * `CommandObservationHook` is gone after this slice.
 *
 * Fail-open is absolute: another method (`spatial` / `active_device` / `control*` / `enable*`), an
 * unmanaged MAC, a missing envelope, an unknown `modeType` or a malformed payload all fall back to
 * `chain.proceed()`, leaving the official behaviour exactly as before.
 */
internal class MelodyProviderCallRedirectInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    fun install() {
        val anchor = MelodyAnchorResolver(log, loader, hostApkPath = null).resolve(
            hook = HOOK,
            baselineClass = PROVIDER_CLASS,
            packages = listOf(PACKAGE),
            methodName = METHOD,
            params = PARAMS,
            returnType = Bundle::class.java,
        ) ?: return
        val dispatchLog = MelodyGroupLog { name, fields -> log.event(name, *fields.toTypedArray()) }
        val dispatcher = MelodyProviderCallRedirectDispatcher(
            host = ProviderBridgeHost(log),
            log = dispatchLog,
            source = SOURCE,
        )
        module.hook(anchor.method).intercept(XposedInterface.Hooker { chain ->
            val methodName = chain.args.getOrNull(0) as? String
            val extras = (chain.args.getOrNull(2) as? Bundle)?.let(::ProviderExtras)
            runCatching {
                log.event(
                    "melody.command.call",
                    "caller" to runCatching { (chain.thisObject as? ContentProvider)?.callingPackage }.getOrNull(),
                    "method" to methodName,
                    "arg" to chain.args.getOrNull(1),
                    "extras" to describeBundle(chain.args.getOrNull(2) as? Bundle),
                )
            }
            val result = dispatcher.handle(methodName, extras) { chain.proceed() }
            runCatching { logDone(methodName, result) }
            result
        })
        log.event(
            "melody.anchor.hooked",
            "hook" to HOOK,
            "class" to anchor.clazz.name,
            "method" to anchor.method.name,
        )
    }

    private fun logDone(methodName: String?, result: Any?) {
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
        const val HOOK = "redirect"
        const val SOURCE = "provider"
        const val PACKAGE = "com.oplus.melody.provider"
        const val PROVIDER_CLASS = "$PACKAGE.EarphoneControlProvider"
        const val METHOD = "call"
        const val MAX_EXTRAS = 10
        const val MAX_CELL_CHARS = 160
        val PARAMS: Array<Class<*>> = arrayOf(String::class.java, String::class.java, Bundle::class.java)
    }
}

/** A [MelodyProviderCallPolicy.Extras] view over the intercepted `Bundle`. */
private class ProviderExtras(private val bundle: Bundle) : MelodyProviderCallPolicy.Extras {

    override fun string(key: String): String? = runCatching { bundle.getString(key) }.getOrNull()

    override fun int(key: String): Int? =
        if (runCatching { bundle.containsKey(key) }.getOrDefault(false)) {
            runCatching { bundle.getInt(key) }.getOrNull()
        } else {
            null
        }
}

/**
 * Everything the provider redirect needs from the host process. Mirrors [MelodySetgateRedirectHost]
 * but the target is the SDK's `extras.type` (`modeType`) and there is no value to build: `call` always
 * returns what `super.call` returned in 17.6.3, i.e. `null`.
 */
internal interface MelodyProviderCallRedirectHost {
    fun managedMacs(): Set<String>

    fun ancPolicy(mac: String): MelodyAncPolicy?

    fun currentAncIndex(mac: String): Int?

    /** Starts the bridge write; the result code arrives on the caller's `onResult`. */
    fun execute(mac: String, actionId: String, args: Map<String, StateValue>, onResult: (Int) -> Unit)
}

/**
 * The decision half of M5.3, shared by the injection and its JVM test. It never throws: every host
 * lookup is wrapped, and a failure degrades to `proceed()`.
 */
internal class MelodyProviderCallRedirectDispatcher(
    private val host: MelodyProviderCallRedirectHost,
    private val log: MelodyGroupLog,
    private val source: String,
) {

    /** Last logged skip reason per MAC, so a retried SDK call does not flood the log with the same line. */
    private val skips = ConcurrentHashMap<String, String>()

    /** Handles one intercepted `call(method, arg, extras)`; returns the value the host's chain must see. */
    fun handle(
        method: String?,
        extras: MelodyProviderCallPolicy.Extras?,
        proceed: () -> Any?,
    ): Any? {
        val command = MelodyProviderCallPolicy.parse(method, extras) ?: return proceed()
        val rawMac = command.mac.trim()
        if (rawMac.isEmpty() || !MelodyMac.isMacAddress(rawMac)) return proceed()
        val key = MelodyMac.normalize(rawMac)
        val managed = runCatching { key in host.managedMacs() }.getOrDefault(false)
        val anc = if (managed) runCatching { host.ancPolicy(key) }.getOrNull() else null
        val current = if (managed) runCatching { host.currentAncIndex(key) }.getOrNull() else null
        return when (
            val decision = MelodyAncRedirectPolicy.decideByModeTypeIncludingChildren(
                key,
                command.modeType,
                managed,
                anc,
                current,
            )
        ) {
            is MelodyAncRedirectPolicy.Decision.Redirect -> {
                // The host's own `L.n` resolves a `modeType` through parents *and* children; log the
                // index it would have written so the E1 line stays comparable with `v0` / `setgate`.
                val index = MelodyAncRedirectPolicy.indexOfModeType(command.modeType, anc, includeChildren = true)
                runCatching {
                    host.execute(key, decision.actionId, decision.args) { code ->
                        if (code != MelodyBridgeResult.OK) {
                            log.event(
                                "melody.redirect.failed",
                                listOf("mac" to key, "action" to decision.actionId, "code" to code),
                            )
                        }
                    }
                }
                log.event(
                    "melody.redirect.anc",
                    listOf(
                        "source" to source,
                        "caller" to hostCaller(),
                        "mac" to key,
                        "method" to method,
                        "type" to command.modeType,
                        "index" to index,
                        "action" to decision.actionId,
                        "args" to renderArgs(decision.args),
                        "mapped" to true,
                    ),
                )
                // 17.6.3 always falls through to `super.call`, so the SDK already consumes `null`;
                // returning it without proceeding is the take-over (D-19).
                null
            }

            is MelodyAncRedirectPolicy.Decision.Skip -> {
                noteSkip(decision.reason, key, command.modeType)
                proceed()
            }
        }
    }

    /** One `melody.redirect.skip` line per (MAC, reason) change, so retries do not flood logcat. */
    private fun noteSkip(reason: String, mac: String, modeType: Int) {
        if (skips.put(mac, reason) == reason) return
        log.event(
            "melody.redirect.skip",
            listOf(
                "source" to source,
                "caller" to hostCaller(),
                "mac" to mac,
                "type" to modeType,
                "reason" to reason,
            ),
        )
    }

    /** The nearest host frame above the hook, e.g. `…EarphoneControlProvider`; `null` when unavailable. */
    private fun hostCaller(): String? =
        runCatching {
            Thread.currentThread().stackTrace.firstOrNull { it.className.startsWith(HOST_PACKAGE_PREFIX) }?.className
        }.getOrNull()

    private fun renderArgs(args: Map<String, StateValue>): String =
        args.entries.joinToString(",") { (name, value) -> "$name=${wireText(value)}" }

    private fun wireText(value: StateValue): String = when (value) {
        is StateValue.BooleanValue -> value.value.toString()
        is StateValue.IntValue -> value.value.toString()
        is StateValue.LongValue -> value.value.toString()
        is StateValue.FloatValue -> value.value.toString()
        is StateValue.DoubleValue -> value.value.toString()
        is StateValue.StringValue -> value.value
        is StateValue.BytesValue -> value.value.joinToString("") { "%02x".format(it) }
        is StateValue.ListValue -> "list"
        is StateValue.MapValue -> "map"
    }

    private companion object {
        const val HOST_PACKAGE_PREFIX = "com.oplus.melody."
    }
}

/** Production [MelodyProviderCallRedirectHost]: wraps the shared bridge client. */
private class ProviderBridgeHost(private val log: MelodyLog) : MelodyProviderCallRedirectHost {

    override fun managedMacs(): Set<String> =
        MelodyBridgeClients.existing()?.managedMacsFast()?.toSet().orEmpty()

    override fun ancPolicy(mac: String): MelodyAncPolicy? {
        val client = MelodyBridgeClients.existing() ?: return null
        // A null identity means "no envelope": distinct from `MelodyAncPolicy.NONE`, which is an
        // envelope whose `anc` node is absent. The policy logs them as no_envelope / no_anc.
        if (client.whitelistIdentityFast(mac) == null) return null
        return client.ancFast(mac)
    }

    override fun currentAncIndex(mac: String): Int? =
        MelodyBridgeClients.existing()?.currentAncIndexFast(mac)

    override fun execute(
        mac: String,
        actionId: String,
        args: Map<String, StateValue>,
        onResult: (Int) -> Unit,
    ) {
        val client = MelodyBridgeClients.existing()
        if (client == null) {
            log.event(
                "melody.redirect.failed",
                "mac" to mac,
                "action" to actionId,
                "code" to MelodyBridgeResult.ERROR_SESSION_UNAVAILABLE,
            )
            onResult(MelodyBridgeResult.ERROR_SESSION_UNAVAILABLE)
            return
        }
        client.executeAsync(mac, actionId, args, onResult)
    }
}

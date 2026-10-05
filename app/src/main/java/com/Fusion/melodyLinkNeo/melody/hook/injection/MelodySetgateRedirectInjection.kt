package com.fusion.melodyLinkNeo.melody.hook.injection

import android.content.Context
import android.content.Intent
import android.os.Binder
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import com.fusion.melodyLinkNeo.melody.api.MelodyAncPolicy
import com.fusion.melodyLinkNeo.melody.api.MelodyAncRedirectPolicy
import com.fusion.melodyLinkNeo.melody.api.MelodyBridgeResult
import com.fusion.melodyLinkNeo.melody.api.MelodyMac
import com.fusion.melodyLinkNeo.melody.api.MelodySetgatePolicy
import com.fusion.melodyLinkNeo.melody.hook.MelodyAnchorResolver
import com.fusion.melodyLinkNeo.melody.hook.MelodyLog
import com.fusion.melodyLinkNeo.melody.hook.bridge.MelodyBridgeClients
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * M5.2: takes over the `com.oplus.melody.setgate` broadcast (`HANDOFF_MELODY_M5_PLAN.md` §4 M5.2,
 * `docs/melody-capability-map.md` §8.5).
 *
 * The device card, the settings app and OneSpace switch ANC by broadcasting `setgate` to
 * `NoiseReductionCommand`, whose `onReceive` resolves the payload's `modeType` and then writes through the
 * collection point M5.1 already owns. Hooking `onReceive` is the second layer: it avoids even the host's
 * own `a(...)` mapping/wear validation and, crucially, stays alive if the `v0` anchor is renamed. The M1
 * read-only observation is folded into this same hook (one hook per method, so a take-over never swallows
 * the control line), and the actual decision is the pure, JVM-tested [MelodySetgateRedirectDispatcher].
 *
 * Fail-open is absolute: an unmanaged MAC, a missing envelope/table, an unknown `modeType` or a malformed
 * payload all fall back to `chain.proceed()`, leaving the official behaviour exactly as before.
 */
internal class MelodySetgateRedirectInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    fun install() {
        val anchor = MelodyAnchorResolver(log, loader, hostApkPath = null).resolve(
            hook = HOOK,
            baselineClass = NOISE_COMMAND_CLASS,
            packages = listOf(PACKAGE),
            methodName = METHOD,
            params = PARAMS,
            returnType = Void.TYPE,
        ) ?: return
        val dispatchLog = MelodyGroupLog { name, fields -> log.event(name, *fields.toTypedArray()) }
        val dispatcher = MelodySetgateRedirectDispatcher(
            host = SetgateBridgeHost(log),
            log = dispatchLog,
            source = SOURCE,
        )
        module.hook(anchor.method).intercept(XposedInterface.Hooker { chain ->
            val context = chain.args.getOrNull(0) as? Context
            val intent = chain.args.getOrNull(1) as? Intent
            runCatching {
                log.event(
                    "melody.command.receive",
                    "sender" to describeUid(context),
                    "action" to intent?.action,
                    "extras" to describeIntent(intent),
                )
            }
            val result = if (intent == null) {
                chain.proceed()
            } else {
                dispatcher.handle(
                    extraJson = runCatching { intent.getStringExtra(EXTRA_EXTRA) }.getOrNull(),
                    from = runCatching { intent.getStringExtra(EXTRA_FROM) }.getOrNull(),
                ) { chain.proceed() }
            }
            runCatching { log.event("melody.command.receive.done", "action" to intent?.action) }
            result
        })
        log.event(
            "melody.anchor.hooked",
            "hook" to HOOK,
            "class" to anchor.clazz.name,
            "method" to anchor.method.name,
        )
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

    private fun describeValue(value: Any?): String = when (value) {
        null -> "null"
        is Array<*> -> value.joinToString(",", "[", "]") { describeValue(it) }
        else -> MelodyLog.sanitize(value.toString(), MAX_CELL_CHARS)
    }

    private companion object {
        const val HOOK = "redirect.setgate"
        const val SOURCE = "setgate"
        const val PACKAGE = "com.oplus.melody.mydevices.devicecard.noisereduction"
        const val NOISE_COMMAND_CLASS = "$PACKAGE.NoiseReductionCommand"
        const val METHOD = "onReceive"
        const val EXTRA_EXTRA = "extra"
        const val EXTRA_FROM = "melody_from"
        const val MAX_EXTRAS = 10
        const val MAX_CELL_CHARS = 160
        val PARAMS: Array<Class<*>> = arrayOf(Context::class.java, Intent::class.java)
    }
}

/**
 * Everything the setgate redirect needs from the host process. Mirrors
 * [MelodyAncRedirectHost] but the target is a `modeType` and there is no value to build (`onReceive`
 * is `void`), so a take-over simply returns `null` without proceeding.
 */
internal interface MelodySetgateRedirectHost {
    fun managedMacs(): Set<String>

    fun ancPolicy(mac: String): MelodyAncPolicy?

    fun currentAncIndex(mac: String): Int?

    /** Starts the bridge write; the result code arrives on the caller's `onResult`. */
    fun execute(mac: String, actionId: String, args: Map<String, StateValue>, onResult: (Int) -> Unit)
}

/**
 * The decision half of M5.2, shared by the injection and its JVM test. It never throws: every host
 * lookup is wrapped, and a failure degrades to `proceed()`.
 */
internal class MelodySetgateRedirectDispatcher(
    private val host: MelodySetgateRedirectHost,
    private val log: MelodyGroupLog,
    private val source: String,
) {

    /** Last logged skip reason per MAC, so a host re-broadcast does not flood the log with the same line. */
    private val skips = ConcurrentHashMap<String, String>()

    /** Handles one intercepted `onReceive(context, intent)`; returns the value the host's chain must see. */
    fun handle(extraJson: String?, from: String?, proceed: () -> Any?): Any? {
        val command = MelodySetgatePolicy.parse(extraJson) ?: return proceed()
        val rawMac = command.mac.trim()
        if (rawMac.isEmpty() || !MelodyMac.isMacAddress(rawMac)) return proceed()
        val key = MelodyMac.normalize(rawMac)
        val managed = runCatching { key in host.managedMacs() }.getOrDefault(false)
        val anc = if (managed) runCatching { host.ancPolicy(key) }.getOrNull() else null
        val current = if (managed) runCatching { host.currentAncIndex(key) }.getOrNull() else null
        return when (
            val decision = MelodyAncRedirectPolicy.decideByModeType(
                key,
                command.modeType,
                managed,
                anc,
                current,
            )
        ) {
            is MelodyAncRedirectPolicy.Decision.Redirect -> {
                val index = anc?.modes?.firstOrNull { it.modeType == command.modeType }?.protocolIndex
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
                        "from" to from,
                        "caller" to hostCaller(),
                        "mac" to key,
                        "type" to command.modeType,
                        "index" to index,
                        "action" to decision.actionId,
                        "args" to renderArgs(decision.args),
                        "mapped" to true,
                    ),
                )
                // `onReceive` is void: returning without proceeding is the take-over. The host's own
                // `a(...)` mapping/wear validation and its write never run, so there is no double control.
                null
            }

            is MelodyAncRedirectPolicy.Decision.Skip -> {
                noteSkip(decision.reason, key, command.modeType)
                proceed()
            }
        }
    }

    /** One `melody.redirect.skip` line per (MAC, reason) change, so re-broadcasts do not flood logcat. */
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

    /** The nearest host frame above the hook, e.g. `…NoiseReductionCommand`; `null` when unavailable. */
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

/** Production [MelodySetgateRedirectHost]: wraps the shared bridge client. */
private class SetgateBridgeHost(private val log: MelodyLog) : MelodySetgateRedirectHost {

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

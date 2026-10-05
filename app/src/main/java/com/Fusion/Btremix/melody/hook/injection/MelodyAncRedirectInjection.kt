package com.Fusion.Btremix.melody.hook.injection

import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.melody.api.MelodyAncPolicy
import com.Fusion.Btremix.melody.api.MelodyAncRedirectPolicy
import com.Fusion.Btremix.melody.api.MelodyBridgeResult
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.hook.MelodyAnchorResolver
import com.Fusion.Btremix.melody.hook.MelodyAnchorSession
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * M5.1: the single collection point for every official ANC write (`HANDOFF_MELODY_M5_PLAN.md` §2.1/§4
 * M5.1, decisions D-17/D-18/D-19).
 *
 * The host funnels all 14 official ANC write call sites - `setgate` broadcast, device-centre SDK,
 * detail-page mode cells, the「降噪效果」popup, OneSpace, the desktop card and XiaoBu voice - into
 * `EarphoneRepositoryClientImpl.v0(protocolIndex, mac)`. Hooking that one method therefore covers every
 * official entrance at once. The translation itself is the pure [MelodyAncRedirectPolicy]; this file
 * only supplies the host-side values (managed set, envelope policy, current index, execute) and builds
 * the return value the original caller expects.
 *
 * Fail-open is absolute: an unmanaged MAC, a missing envelope/action, an unbuildable return value or any
 * throw all fall back to `chain.proceed()`, leaving the official path exactly as it was before the
 * module was installed.
 */
internal class MelodyAncRedirectInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
    /** `ApplicationInfo.sourceDir` of the host, used only by the DexKit fallback. */
    private val hostApkPath: String?,
) {

    fun install() {
        val anchors = MelodyAnchorResolver(log, loader, hostApkPath).resolveAll(
            hook = HOOK,
            baselineClasses = BASELINE_CLASSES,
            packages = listOf(PACKAGE),
            methodName = METHOD,
            params = PARAMS,
            returnType = CompletableFuture::class.java,
        )
        if (anchors.isEmpty()) return
        val dispatchLog = MelodyGroupLog { name, fields -> log.event(name, *fields.toTypedArray()) }
        for (anchor in anchors) {
            val dispatcher = MelodyAncRedirectDispatcher(
                host = BridgeHost(anchor.method, loader, hostApkPath, log),
                log = dispatchLog,
                source = SOURCE,
            )
            module.hook(anchor.method).intercept(XposedInterface.Hooker { chain ->
                val index = (chain.args.getOrNull(0) as? Number)?.toInt()
                if (index == null) {
                    chain.proceed()
                } else {
                    dispatcher.handle(index, chain.args.getOrNull(1) as? String) { chain.proceed() }
                }
            })
            log.event(
                "melody.anchor.hooked",
                "hook" to HOOK,
                "class" to anchor.clazz.name,
                "method" to anchor.method.name,
            )
        }
    }

    private companion object {
        const val HOOK = "redirect.v0"
        const val SOURCE = "v0"
        const val PACKAGE = "com.oplus.melody.model.repository.earphone"
        const val METHOD = "v0"
        /**
         * Both concrete implementations of the abstract `earphone/b;->v0` contract recorded for 17.6.3
         * (`EarphoneRepositoryClientImpl` serves `:fg`, `J` serves the main process). Hooking only the
         * client left the whole main process - `setgate`, the desktop card, XiaoBu - on the official
         * path, which is exactly the "OneSpace click does nothing" failure. A missing baseline makes the
         * resolver run its DexKit level, so a renamed pair is still picked up.
         */
        val BASELINE_CLASSES: List<String> = listOf(
            "$PACKAGE.EarphoneRepositoryClientImpl",
            "$PACKAGE.J",
        )
        val PARAMS: Array<Class<*>> = arrayOf(Int::class.javaPrimitiveType!!, String::class.java)
    }
}

/**
 * Everything the redirect needs from the host process. The production implementation wraps
 * [MelodyBridgeClients] and reflection; JVM tests supply fakes, which is what keeps the branch
 * selection ("redirect vs proceed") testable without an Android device.
 */
internal interface MelodyAncRedirectHost {
    /** The managed set (cached where possible). An empty set means "nothing to take over". */
    fun managedMacs(): Set<String>

    /** The `melody.anc` policy of [mac], or `null` when there is no envelope at all. */
    fun ancPolicy(mac: String): MelodyAncPolicy?

    /** The `protocolIndex` the Definition currently projects for [mac], or `null` when unknown. */
    fun currentAncIndex(mac: String): Int?

    /**
     * Builds the value the intercepted method must return **and** starts the bridge write.
     *
     * The returned future is completed only after `IMelodyBridge.execute` answered, never immediately:
     * the host's own refresh runs when that future completes and re-reads our projection, so completing
     * it early makes the host display the *previous* mode (the state push has not landed yet). This is
     * the same asynchrony the official `v0` had - it also returned a future the host's transport
     * completed later. The UI thread is still never blocked.
     *
     * Returns `null` when the return value cannot be built, in which case the caller falls back to
     * `chain.proceed()` (fail-open, D-19).
     */
    fun redirect(
        rawMac: String,
        mac: String,
        actionId: String,
        args: Map<String, StateValue>,
        onResult: (Int) -> Unit,
    ): Any?
}

/**
 * The decision half of M5.1, shared by the injection and its JVM test. It never throws: every host
 * lookup is wrapped, and a failure degrades to `proceed()`.
 */
internal class MelodyAncRedirectDispatcher(
    private val host: MelodyAncRedirectHost,
    private val log: MelodyGroupLog,
    private val source: String,
) {

    /** Last logged skip reason per MAC, so a host refresh does not flood the log with the same line. */
    private val skips = ConcurrentHashMap<String, String>()

    /** Handles one intercepted `v0(protocolIndex, mac)` and returns the value the host's chain must see. */
    fun handle(protocolIndex: Int, rawMac: String?, proceed: () -> Any?): Any? {
        val mac = rawMac?.trim().orEmpty()
        if (mac.isEmpty() || !MelodyMac.isMacAddress(mac)) return proceed()
        val key = MelodyMac.normalize(mac)
        val managed = runCatching { key in host.managedMacs() }.getOrDefault(false)
        val anc = if (managed) runCatching { host.ancPolicy(key) }.getOrNull() else null
        val current = if (managed) runCatching { host.currentAncIndex(key) }.getOrNull() else null
        return when (val decision = MelodyAncRedirectPolicy.decide(key, protocolIndex, managed, anc, current)) {
            is MelodyAncRedirectPolicy.Decision.Redirect ->
                redirect(key, mac, protocolIndex, current, decision, proceed)

            is MelodyAncRedirectPolicy.Decision.Skip -> {
                noteSkip(decision.reason, key)
                proceed()
            }
        }
    }

    private fun redirect(
        key: String,
        rawMac: String,
        index: Int,
        current: Int?,
        decision: MelodyAncRedirectPolicy.Decision.Redirect,
        proceed: () -> Any?,
    ): Any? {
        val returned = runCatching {
            host.redirect(rawMac, key, decision.actionId, decision.args) { code ->
                if (code != MelodyBridgeResult.OK) {
                    log.event(
                        "melody.redirect.failed",
                        listOf("mac" to key, "action" to decision.actionId, "code" to code),
                    )
                }
            }
        }.getOrNull()
        // Build the return value *before* executing: if it cannot be built, the host must still own the
        // call, or we would have written once and then also let the official path write (double control).
        if (returned == null) {
            log.event(
                "melody.redirect.return_fallback",
                listOf("source" to source, "mac" to key, "index" to index, "action" to decision.actionId),
            )
            return proceed()
        }
        log.event(
            "melody.redirect.anc",
            listOf(
                "source" to source,
                // M5_PLAN §3.1 #3: the three diagnosis self-test call sites reach `v0` too. D-16 still
                // takes them over (their host-side write would be suppressed anyway), and this caller
                // frame is what makes them distinguishable in logcat without a second hook.
                "caller" to hostCaller(),
                "mac" to key,
                "index" to index,
                // What the projection currently says, so a "the headset did not move" report can be
                // told apart from a "our state never advanced" report without another rebuild.
                "current" to current,
                "action" to decision.actionId,
                "args" to renderArgs(decision.args),
                "mapped" to true,
            ),
        )
        return returned
    }

    /** One `melody.redirect.skip` line per (MAC, reason) change, so refreshes do not flood logcat. */
    private fun noteSkip(reason: String, mac: String) {
        if (skips.put(mac, reason) == reason) return
        log.event(
            "melody.redirect.skip",
            listOf("source" to source, "caller" to hostCaller(), "mac" to mac, "reason" to reason),
        )
    }

    /** The nearest host frame above the hook, e.g. `…MelodyAssistantApiProvider` or `…Diagnosis*Fragment`. */
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

/** Production [MelodyAncRedirectHost]: wraps the shared bridge client and the return-value reflection. */
private class BridgeHost(
    private val method: Method,
    private val loader: ClassLoader,
    private val hostApkPath: String?,
    private val log: MelodyLog,
) : MelodyAncRedirectHost {

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

    override fun redirect(
        rawMac: String,
        mac: String,
        actionId: String,
        args: Map<String, StateValue>,
        onResult: (Int) -> Unit,
    ): Any? {
        // Probe the return value first: an unbuildable DTO must fall back to the official path *before*
        // any write happens (otherwise we would write once and let the host write again).
        val dtoClass = MelodyAncStateDto.resolve(method, loader, hostApkPath) ?: return null
        if (MelodyAncStateDto.create(dtoClass, rawMac, MelodyBridgeResult.OK) == null) return null

        val future = CompletableFuture<Any?>()
        val client = MelodyBridgeClients.existing()
        if (client == null) {
            log.event(
                "melody.redirect.failed",
                "mac" to mac,
                "action" to actionId,
                "code" to MelodyBridgeResult.ERROR_SESSION_UNAVAILABLE,
            )
            future.complete(MelodyAncStateDto.create(dtoClass, rawMac, MelodyBridgeResult.ERROR_SESSION_UNAVAILABLE))
            onResult(MelodyBridgeResult.ERROR_SESSION_UNAVAILABLE)
            return future
        }
        client.executeAsync(mac, actionId, args) { code ->
            // Complete only now: the host's continuation (and its own UI refresh) must observe the
            // snapshot push that this write produced, not the state from before it.
            future.complete(MelodyAncStateDto.create(dtoClass, rawMac, code))
            onResult(code)
        }
        return future
    }
}

/**
 * Reflection over the host's `SetCommandStateDTO` (`O` in 17.6.3, `HANDOFF_MELODY_M5_PLAN.md` §5.2 /
 * `docs/melody-capability-map.md` §8.3): `{address: String, setCommandStatus: Int}`, with a public
 * `(String, int)` constructor and `setAddress` / `setSetCommandStatus` setters. Every consumer on the
 * host side only reads those two accessors, so a completed future carrying a success-shaped DTO keeps
 * the original caller happy without it ever touching the transport.
 */
private object MelodyAncStateDto {

    const val PACKAGE = "com.oplus.melody.model.repository.earphone"
    const val BASELINE_CLASS = "$PACKAGE.O"
    const val STATUS_ACCESSOR = "getSetCommandStatus"
    const val ADDRESS_SETTER = "setAddress"
    const val STATUS_SETTER = "setSetCommandStatus"

    fun resolve(method: Method, loader: ClassLoader, hostApkPath: String?): Class<*>? {
        // 1. The method's own generic return type `CompletableFuture<O>`, when the host kept the
        //    signature attribute (the most precise answer).
        ((method.genericReturnType as? ParameterizedType)?.actualTypeArguments?.firstOrNull() as? Class<*>)
            ?.let { return it }
        // 2. The DTO lives in the same package as the intercepted implementation in 17.6.3.
        val pkg = method.declaringClass.name.substringBeforeLast('.', "")
        if (pkg.isNotEmpty()) Reflect.loadClass("$pkg.O", loader)?.let { return it }
        // 3. The catalog anchor resolved by the DTO's own accessor, so a renamed class is still found.
        MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.REDIRECT_V0_RESULT, loader)?.let { return it }
        // 4. Recorded baseline class name.
        return Reflect.loadClass(BASELINE_CLASS, loader)
    }

    fun create(clazz: Class<*>, mac: String, status: Int): Any? {
        runCatching {
            clazz.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType!!)
                .also { it.isAccessible = true }
                .newInstance(mac, status)
        }.getOrNull()?.let { return it }
        val instance = runCatching {
            clazz.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
        }.getOrNull() ?: return null
        if (!Reflect.invokeSingleArg(instance, ADDRESS_SETTER, mac)) return null
        if (!Reflect.invokeSingleArg(instance, STATUS_SETTER, status)) return null
        return instance
    }
}

package com.Fusion.Btremix.melody.hook.injection

import android.os.SystemClock
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.api.MelodyWhitelistIdentity
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.MelodyAnchorSession
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClient
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * M3.4 in-memory whitelist-repository injection (`HANDOFF_MELODY_M3_PLAN.md` §4 M3.4 fallback,
 * `COLOROS_MELODY_ANALYSIS.md` §B/§I1).
 *
 * Why the Provider injection was not enough: the device-centre card is built in the host's **main**
 * process by `com.oplus.melody.app.discovery` / `h9.m` (`EarDeviceCardRepository`), and that code asks
 * the *in-memory* repository — `c9.a.f().c(productId, deviceName)` — whether the bonded headset is a
 * supported model. `c9.a` (`WhitelistRepository`) has two implementations:
 *
 * - `com.oplus.melody.model.repository.whitelist.a` (`WhitelistRepositoryServerImpl`, main process):
 *   answers from the whitelist file/Room data, **never** from the provider — so our `find_whitelist`
 *   row is invisible to it and the card is skipped;
 * - `c9.b` (`WhitelistRepositoryClientImpl`, `:fg`): answers from a cache filled by querying the
 *   provider, so it would see our row, but only after its cache is refreshed.
 *
 * The device run confirmed exactly this: a positive `find_whitelist` answer and a synthesised
 * `DeviceInfo`, yet `melody.devicecard.query` answered `count=0` and no entry appeared.
 *
 * This hook answers the two lookups (`a(mac)` and `c(productId, deviceName)`) for a managed device when
 * the official implementation has nothing, returning a real `WhitelistConfigDTO` rebuilt from the
 * envelope's `whitelist` JSON by the **host's own parser** (`JsonUtils.c(String, Type)`), so the
 * object is indistinguishable from one the host loaded itself.
 *
 * M4.1 追加（真机对照实验的必需前置）：详情页的能力表**不经过**上面两条同步查询。它观察的是
 * `WhitelistRepository.h(productId, deviceName)` 返回的 `LiveData`，而该 LiveData 的实现是
 * `k()`（官方白名单 `WhitelistContentDO` 流）经 `D0.d(0x12, productId, name).apply(content)`
 * 映射而来 —— 一个纯本地查询，永远不会问 `c(...)`。因此只注入 `a`/`c` 的设备在详情页上
 * 必然拿到 `function == null` 的空面板（真机日志 `refreshListView function is null!`），
 * 任何能力位都不会渲染。这里补上那个映射器的 hook：官方映射结果为 `null` 且请求指向托管设备时，
 * 用宿主自己的解析器重建的 DTO 顶上。
 */
internal class MelodyWhitelistRepositoryInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    private val dtos = ConcurrentHashMap<String, Any>()

    @Volatile
    private var managed: Pair<Long, Set<String>>? = null

    /** Resolved once at install time; the mapper hook fires on every panel refresh. */
    @Volatile
    private var contentClassName: String? = null

    fun install() {
        val dtoClass = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.DTO_WHITELIST_CONFIG, loader)
        if (dtoClass == null) {
            log.event("melody.anchor.missing", "hook" to "whitelist_repo", "class" to DTO_CLASS)
            return
        }
        contentClassName = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.DTO_WHITELIST_CONTENT, loader)?.name
        var installed = false
        for (cls in MelodyAnchorSession.classes(MelodyAnchorCatalog.WHITELIST_REPO_IMPL, loader)) {
            installed = hookByMac(cls) || installed
            installed = hookByIdOrName(cls) || installed
        }
        installed = hookPanelLiveDataMapper() || installed
        if (!installed) {
            log.event("melody.anchor.missing", "hook" to "whitelist_repo", "classes" to IMPL_CLASSES.joinToString(","))
        }
    }

    /**
     * `c9.a.h(productId, deviceName)` = `LiveData.map(k()) { D0.d(0x12, productId, name).apply(it) }`.
     *
     * The mapper's `apply` is what the detail page's capability table actually comes from, so hooking it
     * is the difference between "the panel asks us and we answer" and "the panel never asks". The hook
     * only fires for a mapper carrying a `WhitelistContentDO` argument and whose official answer is
     * `null`; anything else is passed through untouched.
     */
    private fun hookPanelLiveDataMapper(): Boolean {
        val classes = MelodyAnchorSession.classes(MelodyAnchorCatalog.WHITELIST_REPO_MAPPER, loader)
        if (classes.isEmpty()) {
            log.event("melody.anchor.missing", "hook" to "whitelist_repo.liveData", "class" to MAPPER_CLASS)
            return false
        }
        var hooked = false
        // Several merged synthetic lambdas can declare `apply`/`accept`; every one is hooked, and the
        // per-call guard below (`WhitelistContentDO` argument + official answer null) picks the real one.
        for (cls in classes) {
            val method = Reflect.findMethod(cls, "apply", arrayOf(Any::class.java)) ?: continue
            if (method.returnType != Any::class.java) continue
            module.hook(method).intercept(XposedInterface.Hooker { chain ->
                val official = chain.proceed()
                val content = chain.args.getOrNull(0)
                if (official != null || !isPanelMapper(chain.thisObject, content)) {
                    official
                } else {
                    answer(
                        via = "byLiveData",
                        key = capturedString(chain.thisObject, CAPTURE_KEY_FIELD),
                        selection = capturedString(chain.thisObject, CAPTURE_NAME_FIELD),
                        owner = MAPPER_CLASS,
                    ) ?: official
                }
            })
            log.event("melody.anchor.hooked", "hook" to "whitelist_repo.liveData", "class" to cls.name, "method" to method.name)
            hooked = true
        }
        if (!hooked) {
            log.event("melody.anchor.missing", "hook" to "whitelist_repo.liveData", "class" to MAPPER_CLASS)
        }
        return hooked
    }

    /** True for the `h(...)` mapper: it consumes the whole official whitelist (`WhitelistContentDO`). */
    private fun isPanelMapper(mapper: Any?, content: Any?): Boolean {
        if (content == null || mapper == null) return false
        return content.javaClass.name == (contentClassName ?: CONTENT_CLASS)
    }

    /**
     * Reads one R8 lambda capture field.
     *
     * [Reflect.readField] deliberately skips synthetic fields (they are usually compiler noise), but an
     * R8-merged synthetic class stores its captures exactly there, so the mapper needs its own read.
     */
    private fun capturedString(mapper: Any?, name: String): String? {
        var type: Class<*>? = mapper?.javaClass
        while (type != null && type != Any::class.java) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(mapper) as? String
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    /** `a(String macAddress) -> WhitelistConfigDTO` */
    private fun hookByMac(cls: Class<*>): Boolean {
        val method = Reflect.findMethod(cls, "a", arrayOf(String::class.java)) ?: return false
        if (method.returnType.name != DTO_CLASS) return false
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val official = chain.proceed()
            if (official != null) {
                official
            } else {
                answer(
                    via = "byMac",
                    key = chain.args.getOrNull(0) as? String,
                    selection = null,
                    owner = cls.simpleName,
                ) ?: official
            }
        })
        log.event("melody.anchor.hooked", "hook" to "whitelist_repo.byMac", "class" to cls.name, "method" to method.name)
        return true
    }

    /** `c(String productId, String deviceName) -> WhitelistConfigDTO` */
    private fun hookByIdOrName(cls: Class<*>): Boolean {
        val params: Array<Class<*>> = arrayOf(String::class.java, String::class.java)
        val method = Reflect.findMethod(cls, "c", params) ?: return false
        if (method.returnType.name != DTO_CLASS) return false
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val official = chain.proceed()
            if (official != null) {
                official
            } else {
                val productId = chain.args.getOrNull(0) as? String
                val name = chain.args.getOrNull(1) as? String
                answer(via = "byProduct", key = productId, selection = name, owner = cls.simpleName) ?: official
            }
        })
        log.event(
            "melody.anchor.hooked",
            "hook" to "whitelist_repo.byProduct",
            "class" to cls.name,
            "method" to method.name,
        )
        return true
    }

    /**
     * The whole-list view (`g()`/`i()`), used by the panel and by `EarDeviceCardRepository` when it
     * enumerates models. It is not hooked: the device-centre card path goes through `c(...)`, and
     * rewriting a 94-entry list on every refresh would be a much larger change for the same result.
     */
    private fun answer(via: String, key: String?, selection: String?, owner: String): Any? {
        val client = MelodyBridgeClients.existing() ?: return null
        val mac = matchManaged(client, key, selection) ?: return null
        val dto = dtoFor(client, mac) ?: return null
        log.event(
            "melody.inject.whitelist_repo",
            "mac" to mac,
            "via" to via,
            "impl" to owner,
            "key" to key,
            "name" to selection,
        )
        return dto
    }

    /**
     * Which managed device the host is asking about: `a(mac)` matches by address, `c(productId, name)`
     * by either encoding of the product id (the host mixes decimal and hex, M3.0) or, when the card
     * code has no product id for a bonded device, by the Bluetooth name.
     */
    private fun matchManaged(client: MelodyBridgeClient, key: String?, name: String?): String? {
        val macs = managedMacs(client)
        if (macs.isEmpty()) return null

        key?.takeIf(MelodyMac::isMacAddress)?.let { mac ->
            val normalized = MelodyMac.normalize(mac)
            if (normalized in macs) return normalized
        }

        val product = key?.trim().orEmpty()
        val wanted = name?.trim().orEmpty()
        for (mac in macs) {
            val identity = client.whitelistIdentityFast(mac) ?: continue
            if (product.isNotEmpty() && matchesProduct(identity, product)) return mac
            if (product.isEmpty() && wanted.isNotEmpty() && matchesName(identity, wanted)) return mac
        }
        return null
    }

    private fun matchesProduct(identity: MelodyWhitelistIdentity, product: String): Boolean {
        val token = product.removePrefix("0x").removePrefix("0X").uppercase()
        return token == identity.hexId.uppercase() ||
            product == identity.decimalId ||
            token.toLongOrNull(16)?.toString() == identity.decimalId
    }

    /** The host's own lookup is a contains-match on the (possibly shortened) Bluetooth name. */
    private fun matchesName(identity: MelodyWhitelistIdentity, name: String): Boolean = when {
        name.equals(identity.name, ignoreCase = true) -> true
        name.length < MIN_NAME_CHARS -> false
        identity.name.contains(name, ignoreCase = true) -> true
        else -> false
    }

    /** Rebuilds (once per device) the DTO the host would have loaded for itself. */
    private fun dtoFor(client: MelodyBridgeClient, mac: String): Any? {
        dtos[mac]?.let { return it }
        val identity = client.whitelistIdentityFast(mac) ?: return null
        val dtoClass = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.DTO_WHITELIST_CONFIG, loader) ?: return null
        val parsed = parse(identity, dtoClass) ?: run {
            log.event("melody.inject.whitelist_repo.parse_failed", "mac" to mac)
            return null
        }
        // A silently-empty parse would hand the host a device with no name/id; verify before use.
        val parsedId = Reflect.callString(parsed, "getId")
        val parsedName = Reflect.callString(parsed, "getName")
        if (parsedId.isNullOrBlank() || parsedName.isNullOrBlank()) {
            log.event(
                "melody.inject.whitelist_repo.parse_failed",
                "mac" to mac,
                "id" to parsedId,
                "name" to parsedName,
            )
            return null
        }
        dtos[mac] = parsed
        return parsed
    }

    /** The host's own JSON helper first; a plain reflective Gson is the fallback. */
    private fun parse(identity: MelodyWhitelistIdentity, dtoClass: Class<*>): Any? {
        Reflect.loadClass(JSON_UTILS_CLASS, loader)?.let { utils ->
            Reflect.invokeStatic2(utils, "c", identity.whitelistJson, dtoClass)?.let { return it }
        }
        val gsonClass = Reflect.loadClass(GSON_CLASS, loader) ?: return null
        val gson = Reflect.newInstance(gsonClass) ?: return null
        return Reflect.invoke2(gson, "fromJson", identity.whitelistJson, dtoClass)
    }

    private fun managedMacs(client: MelodyBridgeClient): Set<String> {
        val now = SystemClock.elapsedRealtime()
        val cached = managed
        if (cached != null && now - cached.first <= MANAGED_TTL_MS) return cached.second
        val next = runCatching { client.managedMacsFast().toSet() }.getOrDefault(emptySet())
        managed = now to next
        return next
    }

    private companion object {
        const val DTO_CLASS = "com.oplus.melody.common.data.WhitelistConfigDTO"
        const val JSON_UTILS_CLASS = "com.oplus.melody.common.util.JsonUtils"
        const val GSON_CLASS = "com.google.gson.Gson"

        /** The official whole-whitelist DO; the `h(...)` mapper is its only consumer here (M4.1). */
        const val CONTENT_CLASS = "com.oplus.melody.common.data.WhitelistContentDO"

        /** R8 name of the mapper class `c9.a.h(...)` captures its (productId, deviceName) in. */
        const val MAPPER_CLASS = "D0.d"

        /** Capture slot names of [MAPPER_CLASS] in the 17.6.3 build (both synthetic). */
        const val CAPTURE_KEY_FIELD = "b"
        const val CAPTURE_NAME_FIELD = "c"

        /**
         * `WhitelistRepositoryServerImpl` (main process, the one the card code uses) and
         * `WhitelistRepositoryClientImpl` (`:fg`). Both are resolved leniently: a host update that
         * drops one only costs that process.
         */
        val IMPL_CLASSES = listOf(
            "com.oplus.melody.model.repository.whitelist.a",
            "c9.b",
        )

        const val MANAGED_TTL_MS = 2_000L

        /** Shorter names are too ambiguous for a contains-match (`Buds`, `XM3`, ...). */
        const val MIN_NAME_CHARS = 5
    }
}

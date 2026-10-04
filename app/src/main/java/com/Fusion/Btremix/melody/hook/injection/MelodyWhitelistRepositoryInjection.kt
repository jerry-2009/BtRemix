package com.Fusion.Btremix.melody.hook.injection

import android.os.SystemClock
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.api.MelodyWhitelistIdentity
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClient
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
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
 */
internal class MelodyWhitelistRepositoryInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    private val dtos = ConcurrentHashMap<String, Any>()

    @Volatile
    private var managed: Pair<Long, Set<String>>? = null

    fun install() {
        val dtoClass = Reflect.loadClass(DTO_CLASS, loader)
        if (dtoClass == null) {
            log.event("melody.anchor.missing", "hook" to "whitelist_repo", "class" to DTO_CLASS)
            return
        }
        var installed = false
        for (className in IMPL_CLASSES) {
            val cls = Reflect.loadClass(className, loader) ?: continue
            installed = hookByMac(cls) || installed
            installed = hookByIdOrName(cls) || installed
        }
        if (!installed) {
            log.event("melody.anchor.missing", "hook" to "whitelist_repo", "classes" to IMPL_CLASSES.joinToString(","))
        }
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
        val dtoClass = Reflect.loadClass(DTO_CLASS, loader) ?: return null
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

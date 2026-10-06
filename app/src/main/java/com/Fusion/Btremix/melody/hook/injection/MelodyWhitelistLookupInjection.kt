package com.Fusion.Btremix.melody.hook.injection

import android.bluetooth.BluetoothDevice
import com.Fusion.Btremix.melody.hook.MelodyAnchorSession
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface

/**
 * 2026-10-06 device-centre card debug: the host's `WhitelistUtils`
 * (`com.oplus.melody.common.util.T`) answers two *collection* lookups -
 *
 * - `a(Collection<WhitelistConfigDTO>, String productId, String name)`
 * - `b(BluetoothDevice, Collection<WhitelistConfigDTO>)`
 *
 * - from a whitelist the caller passes in. Our synthesised row is not in that collection (it only
 * exists inside the repository hooks), so the lookup prints
 * `findWhitelistConfig failed … None by <productId>(<name>)` and returns null; the card rebuild then
 * renders the noise menu as 关闭. That 404 is printed by the host *before* it returns, which is why a
 * post-`proceed` fallback can never hide it (see `HANDOFF_MELODY_ANC_CARD_DEBUG.md` §8.2).
 *
 * This hook answers both lookups from the same in-memory `WhitelistConfigDTO` the repository injection
 * would hand out, **before** the official method runs, and falls back to the host whenever the device is
 * not managed or the bridge is not up.
 *
 * Safety: the host's own repository implementation may call these two methods internally, so a
 * thread-local guard stops our answer from recursing. Everything is fail-open.
 */
internal class MelodyWhitelistLookupInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
    /** Answers by `(productId, deviceName)`; `null` when the identity is not managed. */
    private val answerByProduct: (String?, String?) -> Any?,
    /** Answers by device MAC; `null` when the MAC is not managed. */
    private val answerByMac: (String?) -> Any?,
) {

    /** Guards the re-entrant call the host's repository makes into `a`/`b`. */
    private val answering = ThreadLocal<Boolean>()

    fun install() {
        val cls = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.WHITELIST_UTILS, loader)
        if (cls == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "class" to UTILS_CLASS)
            return
        }
        var hooked = 0
        if (hookByProduct(cls)) hooked += 1
        if (hookByDevice(cls)) hooked += 1
        // Always-on (see MelodyDiagnosticPolicy): this line distinguishes "installed but nothing asked"
        // from "the anchor was missing", which the diagnostics switch could not (LSPosed keeps the
        // remote-preference cache and can hand a freshly started host a stale `false`).
        log.event("melody.injection.whitelist_lookup", "hooked" to hooked, "class" to cls.name)
    }

    /** `a(Collection, String productId, String name) -> WhitelistConfigDTO` */
    private fun hookByProduct(cls: Class<*>): Boolean {
        val method = Reflect.findMethod(cls, "a", PRODUCT_PARAMS) ?: run {
            log.event("melody.anchor.missing", "hook" to "$HOOK.a", "class" to cls.name)
            return false
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val productId = chain.args.getOrNull(1) as? String
            val name = chain.args.getOrNull(2) as? String
            val answer = guarded { answerByProduct(productId, name) }
            if (answer != null) {
                log.event(
                    "melody.inject.whitelist_lookup",
                    "via" to "byProduct",
                    "key" to productId,
                    "name" to name,
                )
                answer
            } else {
                chain.proceed()
            }
        })
        log.event("melody.anchor.hooked", "hook" to "$HOOK.a", "class" to cls.name, "method" to method.name)
        return true
    }

    /** `b(BluetoothDevice, Collection) -> WhitelistConfigDTO` */
    private fun hookByDevice(cls: Class<*>): Boolean {
        val method = Reflect.findMethod(cls, "b", DEVICE_PARAMS) ?: run {
            log.event("melody.anchor.missing", "hook" to "$HOOK.b", "class" to cls.name)
            return false
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            // `BluetoothDevice.getAddress()` needs BLUETOOTH_CONNECT; the host holds it, but a
            // permission flip must not take the card down, so read it defensively.
            val mac = runCatching { (chain.args.getOrNull(0) as? BluetoothDevice)?.address }.getOrNull()
            val answer = guarded { answerByMac(mac) }
            if (answer != null) {
                log.event("melody.inject.whitelist_lookup", "via" to "byDevice", "mac" to mac)
                answer
            } else {
                chain.proceed()
            }
        })
        log.event("melody.anchor.hooked", "hook" to "$HOOK.b", "class" to cls.name, "method" to method.name)
        return true
    }

    /** One answer per thread; the host's repository can call `a`/`b` from inside its own lookup. */
    private inline fun guarded(block: () -> Any?): Any? {
        if (answering.get() == true) return null
        answering.set(true)
        return try {
            block()
        } catch (t: Throwable) {
            log.warn("melody.inject.whitelist_lookup.failed", t)
            null
        } finally {
            answering.set(false)
        }
    }

    private companion object {
        const val HOOK = "whitelist_lookup"

        /** 17.6.3 name; cataloged as `whitelist.utils` with the `a(Collection,String,String)` shape. */
        const val UTILS_CLASS = "com.oplus.melody.common.util.T"

        val PRODUCT_PARAMS: Array<Class<*>> =
            arrayOf(Collection::class.java, String::class.java, String::class.java)
        val DEVICE_PARAMS: Array<Class<*>> =
            arrayOf(BluetoothDevice::class.java, Collection::class.java)
    }
}

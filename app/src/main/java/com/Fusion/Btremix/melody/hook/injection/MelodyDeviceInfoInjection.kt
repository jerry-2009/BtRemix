package com.Fusion.Btremix.melody.hook.injection

import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import com.Fusion.Btremix.melody.api.MelodyDeviceInfoProjection
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.MelodyAnchorSession
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClient
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * M3.4 `DeviceInfoManager` synthesis (`MELODY_BRIDGE_SPEC` §5.2, `HANDOFF_MELODY_M3_PLAN.md` §4 M3.4).
 *
 * The M3.3 probe settled why this slice exists: the provider already answered with our whitelist row,
 * but the host's device registry still answered `null` for the address (`melody.deviceinfo h(...)
 * result=null`) and the system callback carried `productId = 0`, so the detail page was never built.
 *
 * This hook therefore covers both halves of the registry:
 *
 * - **override** — when the host did produce a `DeviceInfo` (`f`/`a`/`b`/`j`), its identity and
 *   connection fields are rewritten from the projection;
 * - **synthesise** — when a lookup (`h`/`d`/`i`) finds nothing, a `DeviceInfo` built from the same
 *   projection is returned *and* put into the manager's own map, so the host's next lookup finds it
 *   natively and the registry stops being the end of the chain.
 *
 * Every write also sets `mIsSupportSpp = false` and `mSppOverGattConnectionState = 0`: that is the
 * first, cheapest transport-suppression layer (§6.3 item 1) and is logged as
 * `melody.transport.suppressed layer=config`.
 *
 * The hot path is the registry polling the host does for every bonded device: the managed set is
 * cached for a couple of seconds, a device that is already projected is returned untouched, and the
 * envelope is only parsed when the identity or the session lifecycle actually changed.
 */
internal class MelodyDeviceInfoInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    /**
     * Anchors from the analysis report §D. [synthesizes] marks the pure lookups: only those may invent
     * a `DeviceInfo`, because returning a fabricated object from the factory/registration paths would
     * hide the host's own bookkeeping from itself.
     */
    private data class Anchor(
        val name: String,
        val params: Array<Class<*>>,
        val label: String,
        val synthesizes: Boolean,
    )

    private data class Projected(val instance: Any, val lifecycle: String?)

    private var deviceInfoClass: Class<*>? = null

    /** Instances already written, so a repeated lookup of the same object costs nothing. */
    private val projected = ConcurrentHashMap<String, Projected>()

    /** Our synthesised `DeviceInfo` per MAC: the host must see one stable object, not a new one. */
    private val synthesized = ConcurrentHashMap<String, Any>()

    @Volatile
    private var managed: Pair<Long, Set<String>>? = null

    fun install() {
        val managerClass = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.BTSDK_DEVICE_INFO_MANAGER, loader)
        if (managerClass == null) {
            log.event("melody.anchor.missing", "hook" to "inject.deviceinfo", "class" to MANAGER_CLASS)
            return
        }
        deviceInfoClass = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.BTSDK_DEVICE_INFO, loader)
        if (deviceInfoClass == null) {
            log.event("melody.anchor.missing", "hook" to "inject.deviceinfo", "class" to DEVICE_INFO_CLASS)
        }
        anchors().forEach { anchor -> hook(managerClass, anchor) }
    }

    private fun anchors(): List<Anchor> = listOf(
        Anchor(
            "f",
            arrayOf(Int::class.javaPrimitiveType!!, BluetoothDevice::class.java, String::class.java, String::class.java),
            "create",
            synthesizes = false,
        ),
        Anchor("h", arrayOf(String::class.java), "findByAddress", synthesizes = true),
        Anchor("d", arrayOf(BluetoothDevice::class.java), "checkGet", synthesizes = true),
        Anchor("i", arrayOf(BluetoothDevice::class.java), "getOrGenerate", synthesizes = true),
        Anchor("a", arrayOf(Int::class.javaPrimitiveType!!, BluetoothDevice::class.java), "addWithProductId", synthesizes = false),
        Anchor("b", arrayOf(Int::class.javaPrimitiveType!!, String::class.java), "addAddressWithProductId", synthesizes = false),
        Anchor("j", arrayOf(Int::class.javaPrimitiveType!!, String::class.java), "setProductId", synthesizes = false),
    )

    private fun hook(managerClass: Class<*>, anchor: Anchor) {
        val method = Reflect.findMethod(managerClass, anchor.name, anchor.params)
            ?: Reflect.findUniqueMethodByParams(managerClass, anchor.params)
        if (method == null) {
            log.event(
                "melody.anchor.missing",
                "hook" to "inject.deviceinfo",
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
            val official = chain.proceed()
            runCatching {
                dispatch(
                    manager = chain.thisObject,
                    anchor = anchor,
                    official = official,
                    device = chain.args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice,
                    addressArg = chain.args.firstOrNull { it is String } as? String,
                )
            }
                .onFailure { log.warn("melody.inject.deviceinfo_failed", it) }
                .getOrNull()
                ?: official
        })
        log.event(
            "melody.anchor.hooked",
            "hook" to "inject.deviceinfo",
            "target" to anchor.label,
            "method" to method.name,
        )
    }

    /**
     * Returns the value the host should see: the official object with our fields written, a synthesised
     * one where the registry had none, or `null` to leave the official answer (not our device, no
     * bridge, unreadable envelope).
     */
    private fun dispatch(
        manager: Any?,
        anchor: Anchor,
        official: Any?,
        device: BluetoothDevice?,
        addressArg: String?,
    ): Any? {
        val mac = addressOf(device, addressArg) ?: return null
        val client = MelodyBridgeClients.existing() ?: return null
        if (!isManaged(client, mac)) return null
        val projection = client.deviceInfoFast(mac) ?: return null

        if (official != null) return override(official, mac, anchor, client, projection)
        if (!anchor.synthesizes) {
            // Factory/registration anchors (`f`/`a`/`b`/`j`) keep their own return value, but a
            // managed device that produced nothing there still belongs in the registry: the M3.3
            // probe showed exactly this (`a(0, mac)` -> null) and the host's later lookups then had
            // nothing to find. Registering here is the same "host memory injection" as below, just
            // triggered by the write path instead of the read path.
            ensureRegistered(manager, mac, projection)
            return null
        }

        val instance = synthesize(mac, projection, anchor.label) ?: return null
        register(manager, mac, instance)
        return instance
    }

    /** Registers (or refreshes) our synthesised device without changing the host's return value. */
    private fun ensureRegistered(manager: Any?, mac: String, projection: MelodyDeviceInfoProjection) {
        val instance = synthesize(mac, projection, "register") ?: return
        register(manager, mac, instance)
    }

    private fun override(
        target: Any,
        mac: String,
        anchor: Anchor,
        client: MelodyBridgeClient,
        projection: MelodyDeviceInfoProjection,
    ): Any {
        val lifecycle = client.lifecycleFast(mac)
        val previous = projected[mac]
        if (previous?.instance === target && previous.lifecycle == lifecycle) return target
        // Never rewrite an object that belongs to a different device: the anchor args are what we
        // matched on, but the returned instance is the host's, and a mismatched return would mean the
        // anchor shape drifted.
        MelodyHostAddress.of(target)?.let { if (it != mac) return target }

        val applied = MelodyDeviceInfoWriter.apply(target, projection)
        projected[mac] = Projected(target, lifecycle)
        log.event(
            "melody.inject.deviceinfo",
            "target" to anchor.label,
            "mac" to mac,
            "action" to "override",
            "product_id" to projection.productId,
            "connected" to projection.connected,
            "setters" to applied.setters,
            "fields" to applied.fields,
        )
        logTransportSuppressed(mac, "config", anchor.label)
        return target
    }

    /**
     * The one object per managed MAC, created once and reused (the host compares registry objects by
     * identity, so handing out a fresh one per lookup would look like the device was replaced).
     */
    private fun synthesize(mac: String, projection: MelodyDeviceInfoProjection, via: String): Any? {
        synthesized[mac]?.let { return it }
        val cls = deviceInfoClass ?: return null
        val instance = Reflect.newInstance(cls) ?: return null
        MelodyDeviceInfoWriter.apply(instance, projection)
        projected[mac] = Projected(instance, null)
        synthesized[mac] = instance
        // Logged once per device, not once per lookup: the registry is polled continuously.
        log.event(
            "melody.inject.deviceinfo",
            "target" to via,
            "mac" to mac,
            "action" to "synthesize",
            "product_id" to projection.productId,
            "connected" to projection.connected,
        )
        logTransportSuppressed(mac, "config", "synthesize")
        return instance
    }

    /**
     * Puts our synthesised device into the manager's own map so the host's later lookups find it
     * without us. The map field is located by type (the analysis report lists `a:ConcurrentHashMap`)
     * because its name is obfuscated; when the field cannot be found the hook still answers the
     * lookup, this only removes the repeated intervention.
     */
    @Suppress("UNCHECKED_CAST")
    private fun register(manager: Any?, mac: String, device: Any) {
        val map = Reflect.readFieldOfType(manager, java.util.Map::class.java) as? MutableMap<Any?, Any?>
            ?: return
        if (map[mac] === device) return
        runCatching { map[mac] = device }
            .onFailure { log.warn("melody.inject.deviceinfo_register_failed", it) }
    }

    private fun logTransportSuppressed(mac: String, layer: String, anchor: String) {
        log.event(
            "melody.transport.suppressed",
            "layer" to layer,
            "mac" to mac,
            "anchor" to anchor,
            "support_spp" to false,
        )
    }

    private fun addressOf(device: BluetoothDevice?, addressArg: String?): String? {
        device?.address?.let(MelodyMac::normalize)?.takeIf(MelodyMac::isMacAddress)?.let { return it }
        val raw = addressArg?.trim() ?: return null
        return if (MelodyMac.isMacAddress(raw)) MelodyMac.normalize(raw) else null
    }

    /**
     * `managedMacsFast()` reads the persisted projection store, which is cheap but still a copy per
     * call; the registry is polled for every bonded device, so the answer is reused for a moment.
     */
    private fun isManaged(client: MelodyBridgeClient, mac: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val cached = managed
        if (cached != null && now - cached.first <= MANAGED_TTL_MS) return mac in cached.second
        val next = runCatching { client.managedMacsFast().toSet() }.getOrDefault(emptySet())
        managed = now to next
        return mac in next
    }

    private companion object {
        const val MANAGER_CLASS = "com.oplus.melody.btsdk.api.manager.DeviceInfoManager"
        const val DEVICE_INFO_CLASS = "com.oplus.melody.btsdk.api.data.DeviceInfo"

        /** Long enough to keep the polling path allocation-free, short enough to notice a pairing. */
        const val MANAGED_TTL_MS = 2_000L
    }
}

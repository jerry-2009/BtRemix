package com.Fusion.Btremix.melody.hook.injection

import android.os.SystemClock
import com.Fusion.Btremix.melody.hook.MelodyAnchorResolver
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClient
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
import io.github.libxposed.api.XposedInterface
import java.util.UUID

/**
 * M3.4 transport suppression, layers 2 and 3 (`MELODY_BRIDGE_SPEC` §6.3,
 * `HANDOFF_MELODY_M3_PLAN.md` §4 M3.4).
 *
 * Layer 1 (the config bit) is delivered by [MelodyDeviceInfoInjection]: the host sees
 * `mIsSupportSpp = false` and `supportSpp = false`, which is what stops Melody entering the connect
 * path in the first place. The M3.3 probe proved the host can still create a `DeviceInfo` from its own
 * bookkeeping though, so the two backstops are kept:
 *
 * - **client** (`BRClientDevice.o(UUID)`): the per-device RFCOMM binding is skipped for a managed MAC,
 *   which leaves the host with a default/never-usable service UUID instead of the one that would take
 *   the headset away from BtRemix;
 * - **write** (`BaseBRConnection.d(byte[], byte[], long)`): the last resort — a frame queued for a
 *   managed MAC is dropped before it reaches the socket.
 *
 * Both classes are re-obfuscated every release, so the anchor is resolved by the recorded short name
 * first (matched by *signature*, not by name) and by DexKit string/signature lookup second; a miss is
 * reported as `melody.anchor.missing` and simply costs that layer.
 */
internal class MelodyTransportInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
    /** `ApplicationInfo.sourceDir` of the host, used only by the DexKit fallback. */
    private val hostApkPath: String?,
) {

    private data class Layer(
        /** Stable `hook=` value of this layer, e.g. `transport.client` (M5.4 D-30). */
        val hook: String,
        /** Short-name class recorded for the Melody 17.6.3 baseline (analysis report §J1/§J2). */
        val baselineClass: String,
        /** Packages the DexKit fallback searches when the baseline name no longer exists. */
        val packages: List<String>,
        val params: Array<Class<*>>,
    )

    @Volatile
    private var managed: Pair<Long, Set<String>>? = null

    fun install() {
        installLayer(
            Layer(
                hook = "transport.client",
                baselineClass = "c7.b",
                packages = listOf("c7"),
                params = arrayOf(UUID::class.java),
            ),
        )
        installLayer(
            Layer(
                hook = "transport.write",
                baselineClass = "d7.a",
                packages = listOf("d7"),
                params = arrayOf(ByteArray::class.java, ByteArray::class.java, Long::class.javaPrimitiveType!!),
            ),
        )
    }

    private fun installLayer(layer: Layer) {
        val anchor = MelodyAnchorResolver(log, loader, hostApkPath).resolveVoid(
            hook = layer.hook,
            baselineClass = layer.baselineClass,
            packages = layer.packages,
            params = layer.params,
        ) ?: return
        val targetClass = anchor.clazz
        val method = anchor.method
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val mac = MelodyHostAddress.of(chain.thisObject)
            if (mac != null && suppress(mac)) {
                log.event(
                    "melody.transport.suppressed",
                    "hook" to layer.hook,
                    "mac" to mac,
                    "anchor" to targetClass.name,
                    "method" to method.name,
                )
                // The layer methods are void: skipping the call is the suppression. Layer 2 leaves the
                // service UUID unbound, layer 3 drops the frame before it reaches the socket.
                null
            } else {
                chain.proceed()
            }
        })
        log.event(
            "melody.anchor.hooked",
            "hook" to layer.hook,
            "class" to targetClass.name,
            "method" to method.name,
        )
    }

    private fun suppress(mac: String): Boolean {
        val client = MelodyBridgeClients.existing() ?: return false
        if (!isManaged(client, mac)) return false
        return client.transportSuppressedFast(mac)
    }

    private fun isManaged(client: MelodyBridgeClient, mac: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val cached = managed
        if (cached != null && now - cached.first <= MANAGED_TTL_MS) return mac in cached.second
        val next = runCatching { client.managedMacsFast().toSet() }.getOrDefault(emptySet())
        managed = now to next
        return mac in next
    }

    private companion object {
        const val MANAGED_TTL_MS = 2_000L
    }
}

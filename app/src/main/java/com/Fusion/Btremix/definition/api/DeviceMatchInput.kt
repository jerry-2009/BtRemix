package com.Fusion.Btremix.definition.api

import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.core.classic.api.ClassicDevice

/**
 * Neutral device identity that [DeviceMatchRule] predicates evaluate
 * (MELODY_BRIDGE_SPEC §11.3, HANDOFF_MELODY_M3_PLAN.md §4 M3.1).
 *
 * Until now a Definition could only be matched against a BLE advertisement. The same Definition has
 * to serve the BLE Explorer, a bonded classic device (SPP sessions) and the Melody provider queries,
 * so matching is split from the transport-specific scan: name/address rules run against any input,
 * while advertisement-only data (service UUID, manufacturer data) never matches a classic or Melody
 * input. Keeping this model next to [DeviceMatchRule] preserves the dependency direction - `definition`
 * does not have to import anything from `melody`.
 */
sealed interface DeviceMatchInput {
    /** Advertised/known name, or `null` when the transport did not provide one. */
    val name: String?

    /** Canonical address (BLE address or classic MAC). Melody inputs carry the provider's MAC. */
    val address: String

    /** A BLE scan result; the only input kind that can carry advertised services/manufacturer data. */
    data class Advertisement(val scan: BleScanResult) : DeviceMatchInput {
        override val name: String? get() = scan.device.name
        override val address: String get() = scan.device.address
    }

    /** A paired/known classic device, as returned by `RfcommManager.bondedDevices()`. */
    data class Classic(val device: ClassicDevice) : DeviceMatchInput {
        override val name: String? get() = device.name
        override val address: String get() = device.address
    }

    /**
     * A Melody provider query. `find_whitelist` selects by `macAddress` or by
     * `productId` + `deviceName`; the hook builds this input so the same Definition rules answer
     * "is this query one of our devices?" without knowing anything else about the panel.
     */
    data class Melody(
        val mac: String,
        override val name: String?,
        val productId: String? = null,
    ) : DeviceMatchInput {
        override val address: String get() = mac
    }
}

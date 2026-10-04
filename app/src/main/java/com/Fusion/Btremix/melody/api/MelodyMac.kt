package com.Fusion.Btremix.melody.api

import java.util.Locale

/**
 * MAC canonicalisation for the bridge, mirroring [com.Fusion.Btremix.device.session.SessionRegistry].
 *
 * Android hands out addresses in several shapes across `BluetoothDevice`, `BleScanResult` and provider
 * extras. The bridge and the registry must agree on the key or the same headset would look like two
 * different devices, so the rule is duplicated here (trim + upper-case, fixed locale) instead of
 * making the pure `melody.api` layer depend on the session package.
 */
object MelodyMac {
    fun normalize(mac: String): String = mac.trim().uppercase(Locale.ROOT)
}

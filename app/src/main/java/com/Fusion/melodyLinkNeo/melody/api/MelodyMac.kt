package com.fusion.melodyLinkNeo.melody.api

import java.util.Locale

/**
 * MAC canonicalisation for the bridge, mirroring [com.fusion.melodyLinkNeo.device.session.SessionRegistry].
 *
 * Android hands out addresses in several shapes across `BluetoothDevice`, `BleScanResult` and provider
 * extras. The bridge and the registry must agree on the key or the same headset would look like two
 * different devices, so the rule is duplicated here (trim + upper-case, fixed locale) instead of
 * making the pure `melody.api` layer depend on the session package.
 */
object MelodyMac {

    /** `AA:BB:CC:DD:EE:FF` (case-insensitive); used to pick an address out of a host object's fields. */
    private val MAC_PATTERN = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    fun normalize(mac: String): String = mac.trim().uppercase(Locale.ROOT)

    fun isMacAddress(value: String): Boolean = MAC_PATTERN.matches(value.trim())
}

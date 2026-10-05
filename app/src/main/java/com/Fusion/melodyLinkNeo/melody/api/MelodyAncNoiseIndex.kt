package com.fusion.melodyLinkNeo.melody.api

/**
 * Pure resolution for host ANC value objects that carry an index but no address (M5.1 follow-up).
 *
 * The device-centre card is not built from the `EarphoneDTO` the rest of M4/M5 projects into; the host
 * builds its noise menu rows in `i9/c.e(...)` from a `com.oplus.melody.btsdk.api.data.CurrentNoiseModeInfo`
 * and reads `getCurrentNoiseReductionModeIndex()` off it (`docs/melody-capability-map.md` §8.1). That
 * object has no MAC field, so the only honest way to answer it from our projection is when exactly one
 * device is managed - with two or more the owner of the object is unknown, and guessing would move the
 * *other* device's menu. That keeps the M4.3b rule ("an ambiguous surface is skipped rather than
 * guessed") and stays fail-open: no single managed MAC means the host's own value stands.
 */
internal object MelodyAncNoiseIndex {

    /** A single managed device and the `protocolIndex` the Definition currently projects for it. */
    data class Target(val mac: String, val index: Int)

    /**
     * The index a value object without an address may carry: `null` unless exactly one distinct MAC is
     * managed *and* [indexOf] knows its projection.
     */
    fun resolve(managedMacs: List<String>, indexOf: (String) -> Int?): Target? {
        val macs = managedMacs.map(MelodyMac::normalize).distinct()
        if (macs.size != 1) return null
        val mac = macs.first()
        val index = indexOf(mac) ?: return null
        return Target(mac, index)
    }
}

package com.Fusion.Btremix.melody.hook.injection

import com.Fusion.Btremix.melody.api.MelodyEarphoneProjection

/**
 * Maps a [MelodyEarphoneProjection] onto the `EarphoneDTO` getters the detail / OneSpace headers read
 * (M4.3a, `HANDOFF_MELODY_M4_PLAN.md` §3).
 *
 * `EarphoneDTO` is an immutable data class (final fields, private constructor, public `copy`), so the
 * projection cannot rewrite the instance without replacing the host object graph - and the header holds
 * the very object the repository published. Intercepting the **getters** instead keeps the host's object
 * identity intact, covers every consumer (both pages, the device-centre card, the ANC VO builder) and is
 * naturally fail-open: any getter that is absent, or any projection value that is `null`, simply returns
 * the official answer.
 *
 * Pure Kotlin on purpose, so the name -> value mapping is pinned by a JVM test.
 */
internal object MelodyEarphoneAdapter {

    /**
     * Getter names resolved on `EarphoneDTO` in the 17.6.3 build. `batteryInfoReceived` is listed last
     * so the battery getters are the ones that decide whether the header's battery row is shown.
     */
    val GETTERS: List<String> = listOf(
        "getConnectionState",
        "getHeadsetConnectionState",
        "getAclConnectionState",
        "getA2dpConnectionState",
        "getLeftBattery",
        "getRightBattery",
        "getBoxBattery",
        "getHeadsetLeftBattery",
        "getHeadsetRightBattery",
        "getHeadsetBoxBattery",
        "isCapabilityReady",
        "isBatteryInfoReceived",
    )

    /**
     * The value the host should see for [getter], or `null` to keep the official answer.
     *
     * The connection states are always concrete: they follow the BtRemix session lifecycle, so a session
     * that goes down reports `DISCONNECTED` and the header falls back to the native "未连接 / 立即连接"
     * (D-6). Battery levels, `isBatteryInfoReceived` and `isCapabilityReady` are `null` when we have
     * nothing to say - a missing level must never be projected as `0`.
     */
    fun overrideFor(getter: String, projection: MelodyEarphoneProjection): Any? = when (getter) {
        "getConnectionState" -> projection.connectionState
        "getHeadsetConnectionState" -> projection.headsetState
        "getAclConnectionState" -> projection.aclState
        "getA2dpConnectionState" -> projection.a2dpState
        "getLeftBattery", "getHeadsetLeftBattery" -> projection.battery?.left
        "getRightBattery", "getHeadsetRightBattery" -> projection.battery?.right
        "getBoxBattery", "getHeadsetBoxBattery" -> projection.battery?.box
        "isCapabilityReady" -> projection.capabilityReady
        "isBatteryInfoReceived" -> projection.batteryInfoReceived
        else -> null
    }
}

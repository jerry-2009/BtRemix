package com.Fusion.Btremix.melody.api

/**
 * The three battery levels the Melody detail header can show (M4.3a, `HANDOFF_MELODY_M4_PLAN.md` §3).
 *
 * They come from the Definition's own `battery.*` session states - the very states the BtRemix Compose
 * page renders - so the two front-ends never disagree. [left]/[right]/[box] stay `null` when the
 * Definition does not (yet) know that level: a missing level must leave the host's own value alone
 * instead of writing a fabricated `0`.
 */
data class MelodyEarphoneBattery(
    val left: Int? = null,
    val right: Int? = null,
    val box: Int? = null,
) {
    val isEmpty: Boolean get() = left == null && right == null && box == null

    companion object {
        val EMPTY: MelodyEarphoneBattery = MelodyEarphoneBattery()

        /** State key the Sony/Cleer dcpkgs use for the two buds. */
        const val STATE_LEFT: String = "battery.left"
        const val STATE_RIGHT: String = "battery.right"

        /**
         * `battery.case` is the spelling both shipped dcpkgs use; `battery.box` is accepted so a future
         * Definition does not need a second injection rule just to rename the key.
         */
        val STATE_BOX_KEYS: List<String> = listOf("battery.case", "battery.box")

        /**
         * Reads the levels out of the decoded session state. `null` when none of the three is present,
         * which is the "no battery to project" case the caller must treat as "do not touch the host".
         */
        fun ofStates(states: Map<String, WireValue>): MelodyEarphoneBattery? {
            val battery = MelodyEarphoneBattery(
                left = levelOf(states[STATE_LEFT]),
                right = levelOf(states[STATE_RIGHT]),
                box = STATE_BOX_KEYS.firstNotNullOfOrNull { key -> levelOf(states[key]) },
            )
            return battery.takeUnless { it.isEmpty }
        }

        /**
         * Reads the levels straight out of an encoded [MelodySnapshot] (M4.3a).
         *
         * `snapshot.state` maps a state **name** to the *entry* bundle `{value, ts, src, q}`, so the
         * entry has to be decoded first; decoding the entry bundle as if it were the value bundle
         * silently yields `Text("")` for every state, which is exactly the bug that made the detail
         * header show "connected" with an empty battery area.
         */
        fun ofSnapshot(snapshot: MelodySnapshot): MelodyEarphoneBattery? {
            if (snapshot.state.isEmpty) return null
            val levels: MutableMap<String, WireValue> = HashMap()
            fun read(key: String) {
                val entry = snapshot.state.getBundle(key) ?: return
                levels[key] = MelodyBundleCodec.decodeEntry(entry).value
            }
            read(STATE_LEFT)
            read(STATE_RIGHT)
            STATE_BOX_KEYS.forEach(::read)
            return ofStates(levels)
        }

        private fun levelOf(value: WireValue?): Int? = when (value) {
            is WireValue.Int32 -> value.value
            is WireValue.Int64 -> value.value.toInt()
            is WireValue.Text -> value.value.trim().toIntOrNull()
            else -> null
        }
    }
}

/**
 * The values M4.3a projects into the host's `com.oplus.melody.model.repository.earphone.EarphoneDTO`
 * getters (`HANDOFF_MELODY_M4_PLAN.md` §3 M4.3a, decisions D-6 / D-9).
 *
 * Why the header needs its own projection: the detail / OneSpace headers decide "connected vs 立即连接"
 * from `EarphoneDTO`, **not** from the `DeviceInfo` M3.4 synthesises, so a session that is up in BtRemix
 * still rendered as "未连接". Both pages share the same DTO, so one projection covers both (D-6).
 *
 * [connectionState] / [headsetState] / [aclState] / [a2dpState] reuse the exact lifecycle -> profile
 * mapping M3.4 already uses ([MelodyDeviceInfoProjection.profileStateOf]) so the header and the registry
 * can never report different link states. The intermediate lifecycles map to `CONNECTING`, which is
 * deliberately *not* "connected": the host must not offer controls on a half-open link.
 *
 * [capabilityReady] is only forced to `true` on a fully ready session and is `null` otherwise, so the
 * host's own readiness logic keeps running while we are not ready (fail-open). The battery fields are
 * `null` whenever there is nothing to say; a `null` means "return the official value", never "write 0".
 *
 * Transport stays exactly as M3 left it: this projection only changes what the header *displays*, and
 * `supportSpp=false` plus the three suppression layers remain the only connection path (D-9, §7 risk row).
 */
data class MelodyEarphoneProjection(
    val connectionState: Int,
    val headsetState: Int,
    val aclState: Int,
    val a2dpState: Int,
    val capabilityReady: Boolean?,
    val battery: MelodyEarphoneBattery?,
) {
    /** `true` only when a level exists; `null` means "leave the host's own `isBatteryInfoReceived`". */
    val batteryInfoReceived: Boolean? get() = if (battery != null) true else null

    val connected: Boolean get() = connectionState == MelodyDeviceInfoProjection.STATE_CONNECTED

    companion object {
        fun from(lifecycle: String?, battery: MelodyEarphoneBattery?): MelodyEarphoneProjection {
            val state = MelodyDeviceInfoProjection.profileStateOf(lifecycle)
            return MelodyEarphoneProjection(
                connectionState = state,
                headsetState = state,
                aclState = state,
                a2dpState = state,
                // Only ever *claims* capability readiness; a false/absent session leaves the host's own
                // answer in place instead of asserting "not ready" over it.
                capabilityReady = if (state == MelodyDeviceInfoProjection.STATE_CONNECTED) true else null,
                battery = battery?.takeUnless { it.isEmpty },
            )
        }
    }
}

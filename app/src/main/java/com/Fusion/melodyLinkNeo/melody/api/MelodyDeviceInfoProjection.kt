package com.fusion.melodyLinkNeo.melody.api

/**
 * The per-device policy that is not part of the official `WhitelistConfigDTO`
 * (HANDOFF_MELODY_M3_PLAN.md §4 M3.4).
 *
 * It travels in the envelope's own `definition` node, so the host never sees it as a whitelist field:
 *
 * - [productType] is the integer `DeviceInfo.mProductType`; `WhitelistConfigDTO.type` only carries the
 *   form-factor token, and the DeviceInfo side needs the number back;
 * - [suppressTransport] mirrors `melody.support.suppressMelodyTransport`, the switch that lets a
 *   Definition opt out of the BRClientDevice/BaseBRConnection suppression layers.
 */
data class MelodyDevicePolicy(
    val productType: Int,
    val suppressTransport: Boolean,
) {
    companion object {
        /** Used when the envelope predates the node or was truncated: keep the neutral defaults. */
        val NEUTRAL: MelodyDevicePolicy = MelodyDevicePolicy(
            productType = 1,
            suppressTransport = true,
        )
    }
}

/**
 * The identity half of a [MelodyDeviceInfoProjection] (M3-D2): everything that only changes when the
 * Definition or the managed set changes, and nothing that depends on the live session.
 *
 * The Melody-side client caches this per MAC so the `DeviceInfoManager` hot path (the host polls the
 * registry for every bonded device) never re-parses the envelope just to answer a lookup.
 */
data class MelodyDeviceInfoIdentity(
    val mac: String,
    val name: String,
    val productId: Int,
    val productType: Int,
    val supportSpp: Boolean,
    val suppressTransport: Boolean,
) {
    companion object {
        /** `null` when the identity has no usable decimal product id (the host field is an `int`). */
        fun of(identity: MelodyWhitelistIdentity, policy: MelodyDevicePolicy): MelodyDeviceInfoIdentity? {
            val productId = identity.decimalId.toIntOrNull() ?: return null
            return MelodyDeviceInfoIdentity(
                mac = MelodyMac.normalize(identity.mac),
                name = identity.name,
                productId = productId,
                productType = policy.productType,
                supportSpp = identity.supportSpp,
                suppressTransport = policy.suppressTransport,
            )
        }
    }
}

/**
 * The fields M3.4 writes into the host's `com.oplus.melody.btsdk.api.data.DeviceInfo`
 * (MELODY_BRIDGE_SPEC §5.2, HANDOFF_MELODY_M3_PLAN.md §4 M3.4).
 *
 * Why a synthesised `DeviceInfo` is needed at all: the M3.3 probe showed the host already receives our
 * whitelist row but its registry still answers `null` for the address, and the device-centre callback
 * carries `productId = 0`. The registry — not the cursor — is what the detail page is built from, so
 * the injection has to answer `h/d/i` and to override what `f/a` produced.
 *
 * Connection state is projected from the session lifecycle instead of an invented state key: the
 * Definition schema has no "connection" state, and the lifecycle already is the single truth BtRemix
 * publishes (`Ready` = profiles up, `Connecting/Connected/Initializing` = link coming up, and so on).
 * The profile codes match `android.bluetooth.BluetoothProfile`.
 *
 * [supportSpp] and [sppState] always describe "Melody must not build its own RFCOMM channel": they are
 * the first — and cheapest — transport-suppression layer (§6.3 item 1).
 */
data class MelodyDeviceInfoProjection(
    val address: String,
    val name: String,
    val productId: Int,
    val productType: Int,
    val supportSpp: Boolean,
    val connected: Boolean,
    val aclState: Int,
    val a2dpState: Int,
    val headsetState: Int,
    val sppState: Int,
) {
    companion object {
        /** `android.bluetooth.BluetoothProfile` profile states, repeated here to stay Android-free. */
        const val STATE_DISCONNECTED: Int = 0
        const val STATE_CONNECTING: Int = 1
        const val STATE_CONNECTED: Int = 2
        const val STATE_DISCONNECTING: Int = 3

        fun from(identity: MelodyDeviceInfoIdentity, lifecycle: String?): MelodyDeviceInfoProjection {
            val state = profileStateOf(lifecycle)
            return MelodyDeviceInfoProjection(
                address = identity.mac,
                name = identity.name,
                productId = identity.productId,
                productType = identity.productType,
                supportSpp = identity.supportSpp,
                connected = state == STATE_CONNECTED,
                aclState = state,
                a2dpState = state,
                headsetState = state,
                // Suppression layer 1: the host sees "no SPP channel" and never enters the connect path.
                sppState = STATE_DISCONNECTED,
            )
        }

        /**
         * Session lifecycle -> profile state. `Ready`/`RefreshingState` mean the protocol session is
         * up, i.e. the ACL and its profiles are usable; the intermediate states are deliberately
         * reported as `CONNECTING` so the host does not offer controls on a half-open link.
         */
        fun profileStateOf(lifecycle: String?): Int = when (lifecycle) {
            MelodyLifecycleWire.READY, MelodyLifecycleWire.REFRESHING_STATE -> STATE_CONNECTED
            MelodyLifecycleWire.CONNECTING,
            MelodyLifecycleWire.CONNECTED,
            MelodyLifecycleWire.INITIALIZING,
            -> STATE_CONNECTING
            MelodyLifecycleWire.DISCONNECTING -> STATE_DISCONNECTING
            else -> STATE_DISCONNECTED
        }
    }
}

package com.fusion.melodyLinkNeo.melody.api

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * The AIDL-crossing session snapshot (MELODY_BRIDGE_SPEC §8).
 *
 * It is intentionally *not* the runtime's [com.fusion.melodyLinkNeo.device.session.SessionSnapshot]: the wire
 * form is lossy on purpose (lifecycle becomes a name, errors become a message) so the panel never
 * receives a BtRemix class it cannot load. [state] is a `Bundle` of key -> encoded `Bundle` produced by
 * [MelodyBundleCodec], which keeps the parcelable free of any dependency on the runtime hierarchy.
 */
class MelodySnapshot(
    @JvmField val mac: String,
    @JvmField val lifecycle: String,
    @JvmField val errorMessage: String?,
    @JvmField val state: Bundle,
) : Parcelable {

    val stateKeys: Set<String> get() = state.keySet()

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(mac)
        dest.writeString(lifecycle)
        dest.writeString(errorMessage)
        dest.writeBundle(state)
    }

    override fun toString(): String = "MelodySnapshot($mac, $lifecycle, keys=${state.size()})"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<MelodySnapshot> = object : Parcelable.Creator<MelodySnapshot> {
            override fun createFromParcel(source: Parcel): MelodySnapshot = MelodySnapshot(
                mac = source.readString().orEmpty(),
                lifecycle = source.readString() ?: MelodyLifecycleWire.DISCONNECTED,
                errorMessage = source.readString(),
                state = source.readBundle(MelodySnapshot::class.java.classLoader) ?: Bundle(),
            )

            override fun newArray(size: Int): Array<MelodySnapshot?> = arrayOfNulls(size)
        }

        /**
         * Graceful-degradation payload: the MAC is not managed (no live session) so the panel renders
         * "disconnected" instead of failing the whole bridge call (MELODY_BRIDGE_SPEC §12 M2b).
         */
        fun disconnected(mac: String): MelodySnapshot = MelodySnapshot(
            mac = MelodyMac.normalize(mac),
            lifecycle = MelodyLifecycleWire.DISCONNECTED,
            errorMessage = null,
            state = Bundle(),
        )
    }
}

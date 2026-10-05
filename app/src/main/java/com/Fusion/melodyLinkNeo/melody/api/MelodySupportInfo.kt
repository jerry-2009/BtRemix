package com.fusion.melodyLinkNeo.melody.api

import android.os.Parcel
import android.os.Parcelable

/**
 * Synthesised "this headset is officially supported" identity (MELODY_BRIDGE_SPEC §5, §8).
 *
 * M2b only populated [managed] (whether BtRemix held a session for [mac]). M3.1 fills the identity
 * fields from the Definition's `melody` section; when only a live session exists (no `melody`
 * Definition) `resolveSupport` still answers `managed = true` with the identity left empty.
 */
class MelodySupportInfo(
    @JvmField val mac: String,
    @JvmField val managed: Boolean,
    @JvmField val name: String?,
    @JvmField val brand: String?,
    @JvmField val productId: String?,
    @JvmField val productType: Int,
    @JvmField val uuid: String?,
    @JvmField val supportSpp: Boolean,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(mac)
        dest.writeInt(if (managed) 1 else 0)
        dest.writeString(name)
        dest.writeString(brand)
        dest.writeString(productId)
        dest.writeInt(productType)
        dest.writeString(uuid)
        dest.writeInt(if (supportSpp) 1 else 0)
    }

    override fun toString(): String = "MelodySupportInfo($mac, managed=$managed, name=$name, id=$productId)"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<MelodySupportInfo> = object : Parcelable.Creator<MelodySupportInfo> {
            override fun createFromParcel(source: Parcel): MelodySupportInfo = MelodySupportInfo(
                mac = source.readString().orEmpty(),
                managed = source.readInt() != 0,
                name = source.readString(),
                brand = source.readString(),
                productId = source.readString(),
                productType = source.readInt(),
                uuid = source.readString(),
                supportSpp = source.readInt() != 0,
            )

            override fun newArray(size: Int): Array<MelodySupportInfo?> = arrayOfNulls(size)
        }

        /** Fallback for a MAC with a live session but no `melody` Definition: identity stays empty. */
        fun managedOnly(mac: String, managed: Boolean): MelodySupportInfo = MelodySupportInfo(
            mac = MelodyMac.normalize(mac),
            managed = managed,
            name = null,
            brand = null,
            productId = null,
            productType = 1,
            uuid = null,
            supportSpp = false,
        )
    }
}

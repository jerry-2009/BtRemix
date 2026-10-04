package com.Fusion.Btremix.melody.api

import android.os.Parcel
import android.os.Parcelable

/**
 * Synthesised "this headset is officially supported" identity (MELODY_BRIDGE_SPEC §5, §8).
 *
 * M2b only populates [managed] (whether BtRemix holds a session for [mac]); the identity fields are the
 * shape M3 fills from the definition's `melody` section when it answers the host's whitelist queries.
 * Shipping the full shape now means the AIDL contract - and therefore the client and the service - do
 * not have to change again when M3 lands.
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

        /** M2b placeholder: identity fields stay empty until M3 parses the `melody` definition section. */
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

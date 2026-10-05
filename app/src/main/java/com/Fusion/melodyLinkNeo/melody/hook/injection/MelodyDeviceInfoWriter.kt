package com.fusion.melodyLinkNeo.melody.hook.injection

import com.fusion.melodyLinkNeo.melody.api.MelodyDeviceInfoProjection
import com.fusion.melodyLinkNeo.melody.hook.Reflect

/**
 * Writes a [MelodyDeviceInfoProjection] into a host `DeviceInfo` instance (M3.4 plan §4).
 *
 * Every field is applied twice-once: first through the host setter (`setProductId`, ...), and only when
 * that setter is absent or renamed through the backing field listed in the analysis report. Both paths
 * are reflection over the *target object*, so this class has no compile-time dependency on the host and
 * can be unit tested on the JVM against a stand-in with the same field/setter names.
 */
internal object MelodyDeviceInfoWriter {

    /** What was actually written, for the `melody.inject.deviceinfo` log line. */
    data class Applied(val setters: Int, val fields: Int) {
        val total: Int get() = setters + fields
    }

    /**
     * Applies [projection] to [target]; returns how each field was written. A field the host renamed
     * both the setter and the backing field of is simply not written — the caller logs the resulting
     * count so a version drift shows up as "fewer fields" instead of silently.
     */
    fun apply(target: Any, projection: MelodyDeviceInfoProjection): Applied {
        var setters = 0
        var fields = 0
        fun write(setter: String, fieldNames: Array<String>, value: Any?) {
            if (Reflect.invokeSingleArg(target, setter, value)) {
                setters++
            } else if (Reflect.writeField(target, fieldNames, value)) {
                fields++
            }
        }

        write("setDeviceAddress", arrayOf("mDeviceAddress", "deviceAddress", "address"), projection.address)
        write("setDeviceName", arrayOf("mDeviceName", "deviceName", "name"), projection.name)
        write("setProductId", arrayOf("mProductId", "productId"), projection.productId)
        write("setProductType", arrayOf("mProductType", "productType"), projection.productType)
        write("setSupportSpp", arrayOf("mIsSupportSpp", "isSupportSpp", "supportSpp"), projection.supportSpp)
        write("setSppOverGattConnectionState", arrayOf("mSppOverGattConnectionState", "sppOverGattConnectionState"), projection.sppState)
        write("setConnected", arrayOf("mConnected", "connected"), projection.connected)
        write("setDeviceAclConnectState", arrayOf("mDeviceAclConnectState", "deviceAclConnectState"), projection.aclState)
        write("setDeviceA2dpConnectState", arrayOf("mDeviceA2dpConnectState", "deviceA2dpConnectState"), projection.a2dpState)
        write("setDeviceHeadsetConnectState", arrayOf("mDeviceHeadsetConnectState", "deviceHeadsetConnectState"), projection.headsetState)

        return Applied(setters = setters, fields = fields)
    }
}

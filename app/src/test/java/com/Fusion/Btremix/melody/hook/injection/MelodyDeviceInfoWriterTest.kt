package com.Fusion.Btremix.melody.hook.injection

import com.Fusion.Btremix.melody.api.MelodyDeviceInfoProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.4 acceptance for the reflection writer (`HANDOFF_MELODY_M3_PLAN.md` §4 M3.4): the projection has
 * to reach the host `DeviceInfo` through whichever half survived obfuscation - the setter, or the
 * backing field the analysis report lists.
 *
 * The stand-ins below mirror the host's field and method names, which is exactly what the writer
 * matches on, so a rename of the writer's targets shows up here instead of only on a phone.
 */
class MelodyDeviceInfoWriterTest {

    private val projection = MelodyDeviceInfoProjection(
        address = "14:3F:A6:02:5F:B0",
        name = "Sony WF-1000XM3",
        productId = 3296,
        productType = 1,
        supportSpp = false,
        connected = true,
        aclState = 2,
        a2dpState = 2,
        headsetState = 2,
        sppState = 0,
    )

    @Test
    fun writesThroughTheHostSettersWhenTheyExist() {
        val target = SetterDeviceInfo()

        val applied = MelodyDeviceInfoWriter.apply(target, projection)

        assertEquals(10, applied.setters)
        assertEquals(0, applied.fields)
        assertEquals("14:3F:A6:02:5F:B0", target.deviceAddress)
        assertEquals("Sony WF-1000XM3", target.deviceName)
        assertEquals(3296, target.productId)
        assertEquals(1, target.productType)
        assertEquals(false, target.supportSpp)
        assertEquals(true, target.connected)
        assertEquals(2, target.a2dpConnectState)
        assertEquals(2, target.headsetConnectState)
        assertEquals(2, target.aclConnectState)
        assertEquals(0, target.sppOverGattConnectionState)
    }

    @Test
    fun fallsBackToTheBackingFieldsWhenTheSetterWasRenamed() {
        val target = FieldOnlyDeviceInfo()

        val applied = MelodyDeviceInfoWriter.apply(target, projection)

        assertEquals(0, applied.setters)
        assertEquals(10, applied.fields)
        assertEquals("14:3F:A6:02:5F:B0", target.mDeviceAddress)
        assertEquals("Sony WF-1000XM3", target.mDeviceName)
        assertEquals(3296, target.mProductId)
        assertEquals(false, target.mIsSupportSpp)
        assertEquals(0, target.mSppOverGattConnectionState)
        assertEquals(2, target.mDeviceAclConnectState)
        assertEquals(2, target.mDeviceA2dpConnectState)
        assertEquals(2, target.mDeviceHeadsetConnectState)
    }

    @Test
    fun reportsHowManyFieldsCouldNotBeWritten() {
        val target = object {
            @Suppress("unused")
            val onlyName: String = ""
        }

        val applied = MelodyDeviceInfoWriter.apply(target, projection)

        assertEquals(0, applied.total)
        assertTrue(applied.setters == 0 && applied.fields == 0)
    }

    /** Host shape that kept every setter (the happy path on the 17.6.3 baseline). */
    @Suppress("unused", "MemberVisibilityCanBePrivate")
    private class SetterDeviceInfo {
        // `@JvmField` keeps Kotlin from generating accessors that would clash with the explicit
        // setters below (the host's own setters are ordinary methods, not Kotlin properties).
        @JvmField var deviceAddress: String = ""
        @JvmField var deviceName: String = ""
        @JvmField var productId: Int = 0
        @JvmField var productType: Int = 0
        @JvmField var supportSpp: Boolean = true
        @JvmField var connected: Boolean = false
        @JvmField var aclConnectState: Int = 0
        @JvmField var a2dpConnectState: Int = 0
        @JvmField var headsetConnectState: Int = 0
        @JvmField var sppOverGattConnectionState: Int = 1

        fun setDeviceAddress(value: String) { deviceAddress = value }
        fun setDeviceName(value: String) { deviceName = value }
        fun setProductId(value: Int) { productId = value }
        fun setProductType(value: Int) { productType = value }
        fun setSupportSpp(value: Boolean) { supportSpp = value }
        fun setConnected(value: Boolean) { connected = value }
        fun setDeviceAclConnectState(value: Int) { aclConnectState = value }
        fun setDeviceA2dpConnectState(value: Int) { a2dpConnectState = value }
        fun setDeviceHeadsetConnectState(value: Int) { headsetConnectState = value }
        fun setSppOverGattConnectionState(value: Int) { sppOverGattConnectionState = value }
    }

    /** Host shape whose setters were stripped: only the R8-preserved fields remain. */
    @Suppress("unused")
    private class FieldOnlyDeviceInfo {
        @JvmField var mDeviceAddress: String = ""
        @JvmField var mDeviceName: String = ""
        @JvmField var mProductId: Int = 0
        @JvmField var mProductType: Int = 0
        @JvmField var mIsSupportSpp: Boolean = true
        @JvmField var mConnected: Boolean = false
        @JvmField var mDeviceAclConnectState: Int = 0
        @JvmField var mDeviceA2dpConnectState: Int = 0
        @JvmField var mDeviceHeadsetConnectState: Int = 0
        @JvmField var mSppOverGattConnectionState: Int = 1
    }
}

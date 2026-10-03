package com.Fusion.Btremix.definition.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 5: exported bytes must be a package the production reader accepts. */
class DevicePackageWriterTest {
    @Test
    fun encodedPackage_roundTripsThroughReaderAndValidator() {
        val bytes = DevicePackageWriter.encode(
            packageId = "vendor.exported",
            version = "1.4.0",
            definitionJson = definitionJson(id = "vendor.exported", version = "1.4.0", displayName = "Exported"),
        )

        val devicePackage = DevicePackageValidator().validate(DevicePackageReader().read(bytes, "exported.dcpkg"))

        assertEquals("vendor.exported", devicePackage.packageId)
        assertEquals("1.4.0", devicePackage.version)
        assertEquals("Exported", devicePackage.displayName)
        assertTrue(devicePackage.assets.isEmpty())
    }

    @Test
    fun encodedPackage_includesAssetsWhenProvided() {
        val bytes = DevicePackageWriter.encode(
            packageId = "vendor.assets",
            version = "1.0.0",
            definitionJson = definitionJson(id = "vendor.assets", displayName = "Assets"),
            assets = listOf("icon.png" to byteArrayOf(0x89.toByte(), 0x50)),
        )

        val devicePackage = DevicePackageValidator().validate(DevicePackageReader().read(bytes, "assets.dcpkg"))

        assertEquals(listOf("assets/icon.png"), devicePackage.assets)
    }
}

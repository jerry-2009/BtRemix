package com.fusion.melodyLinkNeo.core.bluetooth.api

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleModelsTest {
    @Test
    fun characteristicProperties_mapAndroidFlags() {
        val properties = BleCharacteristicProperty.fromAndroidProperties(0x02 or 0x10 or 0x20)

        assertEquals(
            setOf(
                BleCharacteristicProperty.READ,
                BleCharacteristicProperty.NOTIFY,
                BleCharacteristicProperty.INDICATE,
            ),
            properties,
        )
    }

    @Test
    fun deviceAndServiceModels_areVendorNeutral() {
        val serviceUuid = UUID.randomUUID()
        val device = BleDevice(id = "AA:BB", name = "Peripheral")
        val service = BleService(serviceUuid)

        assertEquals("AA:BB", device.address)
        assertTrue(service.isPrimary)
    }
}

package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.core.bluetooth.api.BleCharacteristic
import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.core.bluetooth.api.BleService
import com.Fusion.Btremix.definition.api.DeviceMatchRule
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.matcher.DefinitionMatcher
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 4: a device whose advertised data is anonymous can still match after service discovery. */
class DeviceGattMatchTest {
    private val definition = DefinitionJsonCodec.decode(declarativeDefinition)

    @Test
    fun scanMatch_returnsNullForAnonymousDevice() {
        val registry = DevicePackageRegistry().apply { register(DevicePackage.builtIn(definition)) }

        assertNull(registry.match(BleScanResult(BleDevice("AA:BB", "Unknown"), rssi = -60)))
    }

    @Test
    fun serviceMatch_recognisesDefinitionThatNeedsGattDiscovery() {
        val registry = DevicePackageRegistry().apply { register(DevicePackage.builtIn(definition)) }

        val match = registry.match(discoveredServices())

        assertNotNull(match)
        assertEquals("gatt.only", match!!.devicePackage.packageId)
        assertTrue(match.rule is DeviceMatchRule.ServiceUuid)
        assertEquals(DefinitionMatcher.IMPLICIT_SERVICE_PRIORITY, match.priority)
    }

    @Test
    fun explicitServiceMatcher_outranksTheImplicitTransportService() {
        val withMatcher = DefinitionJsonCodec.decode(explicitServiceRuleDefinition)
        val registry = DevicePackageRegistry().apply { register(DevicePackage.builtIn(withMatcher)) }

        val match = registry.match(discoveredServices())

        assertEquals(210, match?.priority)
    }

    private fun discoveredServices() = listOf(
        BleService(
            UUID.fromString(VENDOR_SERVICE),
            characteristics = listOf(BleCharacteristic(UUID.fromString(VENDOR_SERVICE), UUID.fromString(VENDOR_CHARACTERISTIC))),
        ),
    )

    private companion object {
        const val VENDOR_SERVICE = "11112222-3333-4444-5555-666677778888"
        const val VENDOR_CHARACTERISTIC = "9999aaaa-bbbb-cccc-dddd-eeeeffff0000"
        const val ANONYMOUS_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb" // a discovered bus, not declared

        val declarativeDefinition = """
            {
              "manifest": {
                "id": "gatt.only",
                "displayName": "GATT only",
                "version": "1.0.0",
                "schemaVersion": 2,
                "matchers": [{ "type": "namePrefix", "value": "NeverMatches" }]
              },
              "protocol": {
                "transport": { "service": "$VENDOR_SERVICE", "characteristic": "$VENDOR_CHARACTERISTIC" }
              },
              "states": {
                "raw": { "type": "bytes", "notify": { "service": "$ANONYMOUS_SERVICE", "characteristic": "00002a19-0000-1000-8000-00805f9b34fb" } }
              }
            }
        """.trimIndent()

        val explicitServiceRuleDefinition = """
            {
              "manifest": {
                "id": "gatt.explicit",
                "displayName": "GATT explicit",
                "version": "1.0.0",
                "matchers": [{ "type": "serviceUuid", "value": "$VENDOR_SERVICE", "priority": 210 }]
              }
            }
        """.trimIndent()
    }
}

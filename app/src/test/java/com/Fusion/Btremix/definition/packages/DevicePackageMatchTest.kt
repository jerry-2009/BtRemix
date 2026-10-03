package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.core.bluetooth.api.BleDevice
import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.definition.api.DeviceMatchRule
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.matcher.DefinitionMatcher
import com.Fusion.Btremix.definition.session.DefinitionSessionFactory
import com.Fusion.Btremix.device.runtime.DefaultDeviceRuntime
import com.Fusion.Btremix.device.runtime.FakeBleConnection
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.device.runtime.StateValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the M2.1/M2.2 seam: scan matching selects a package, which then opens a bound session. */
class DevicePackageMatchTest {
    private val definition = DefinitionJsonCodec.decode(definitionJson())
    private val registry = DevicePackageRegistry().apply {
        register(DevicePackage.builtIn(definition))
    }

    @Test
    fun match_reportsPackageRuleAndPriority() {
        val match = registry.match(scan("Vendor Buds"))

        assertNotNull(match)
        assertEquals("vendor.device", match!!.devicePackage.packageId)
        assertEquals(50, match.priority)
        assertTrue(match.rule is DeviceMatchRule.NamePrefix)
        assertEquals("name starts with \"Vendor\"", DefinitionMatcher.label(match.rule))
        assertEquals("vendor.device", registry.findMatch(scan("Vendor Buds"))?.packageId)
    }

    @Test
    fun match_returnsNullForUnrecognizedDevices() {
        assertNull(registry.match(scan("Unknown Device")))
        assertNull(registry.findMatch(scan("Unknown Device")))
    }

    @Test
    fun matchedScanOpensASessionWithSeededState() = runBlocking {
        val scan = scan("Vendor Buds")
        val matched = registry.match(scan)?.devicePackage ?: error("expected a match")
        val session = DefinitionSessionFactory(DefaultDeviceRuntime()).open(
            device = scan.device,
            connection = FakeBleConnection(),
            definition = matched.definition,
        )

        assertEquals(StateValue.IntValue(50), session.state.value("battery"))
        assertEquals(StateSource.INITIAL, session.state["battery"]?.source)
        session.close()
    }

    private fun scan(name: String) = BleScanResult(BleDevice("AA:BB:CC", name), rssi = -40)
}

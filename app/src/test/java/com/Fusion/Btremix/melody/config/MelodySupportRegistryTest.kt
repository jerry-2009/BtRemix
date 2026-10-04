package com.Fusion.Btremix.melody.config

import com.Fusion.Btremix.core.classic.api.ClassicDevice
import com.Fusion.Btremix.core.classic.api.RfcommConnection
import com.Fusion.Btremix.core.classic.api.RfcommManager
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.packages.DevicePackage
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.1 acceptance (`HANDOFF_MELODY_M3_PLAN.md` §4): a paired device is "managed" only when a
 * registered Definition with a `melody` section matches it. A Definition without the section must
 * stay invisible, which is what protects every pre-M3 package.
 */
class MelodySupportRegistryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rfcomm = FakeRfcommManager()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun pairedMatchingDevice_isManaged() = runBlocking {
        val registry = registry()

        registry.refreshFrom(listOf(ClassicDevice(MAC, "WF-1000XM3")))

        assertEquals(listOf(MAC), registry.managedMacs())
        assertEquals(listOf(MAC), registry.managedMacsFlow.value)
        assertEquals("sony.wf1000xm3", registry.support(MAC)?.packageId)
        assertEquals("Sony WF-1000XM3", registry.support(MAC)?.melody?.support?.name)
    }

    @Test
    fun unpairedOrNonMatchingDevice_isNotManaged() = runBlocking {
        val registry = registry()

        registry.refreshFrom(listOf(ClassicDevice("AA:BB:CC:DD:EE:01", "Some Speaker")))
        assertTrue(registry.managedMacs().isEmpty())
        assertNull(registry.support("AA:BB:CC:DD:EE:01"))

        // A device that is present but not bonded is not a support-injection target.
        registry.refreshFrom(listOf(ClassicDevice(MAC, "WF-1000XM3", bonded = false)))
        assertTrue(registry.managedMacs().isEmpty())
    }

    @Test
    fun definitionWithoutMelodySection_isNeverManaged() = runBlocking {
        val registry = registry(Definitions.plain)

        registry.refreshFrom(listOf(ClassicDevice(MAC, "WF-1000XM3")))

        assertTrue(registry.managedMacs().isEmpty())
        assertNull(registry.support(MAC))
    }

    @Test
    fun highestPriorityDefinitionWinsForTheSameDevice() = runBlocking {
        val registry = registry(Definitions.lowPriority, Definitions.highPriority)

        registry.refreshFrom(listOf(ClassicDevice(MAC, "WF-1000XM3")))

        assertEquals("sony.high", registry.support(MAC)?.packageId)
    }

    @Test
    fun refresh_dropsDevicesThatAreNoLongerBonded() = runBlocking {
        val registry = registry()

        registry.refreshFrom(listOf(ClassicDevice(MAC, "WF-1000XM3")))
        assertEquals(listOf(MAC), registry.managedMacs())

        registry.refreshFrom(emptyList())
        assertTrue(registry.managedMacs().isEmpty())
        assertNull(registry.support(MAC))
    }

    private fun registry(vararg definitions: LoadedDeviceDefinition): MelodySupportRegistry {
        val effective = if (definitions.isEmpty()) arrayOf(Definitions.sony) else definitions
        val packages = MutableStateFlow(
            effective.map { DevicePackage.builtIn(it) },
        )
        return MelodySupportRegistry(packages, rfcomm, scope)
    }

    private companion object {
        const val MAC = "14:3F:A6:02:5F:B0"
    }

    /** Melody definitions used by the tests; `plain` deliberately omits the section. */
    private object Definitions {
        val sony = decode(
            id = "sony.wf1000xm3",
            displayName = "Sony WF-1000XM3",
            melody = true,
            priority = 60,
            name = "Sony WF-1000XM3",
        )
        val plain = decode(id = "sony.plain", displayName = "Plain", melody = false, priority = 10, name = null)
        val lowPriority: LoadedDeviceDefinition =
            decode(id = "sony.low", displayName = "Low", melody = true, priority = 40, name = "Low")
        val highPriority: LoadedDeviceDefinition =
            decode(id = "sony.high", displayName = "High", melody = true, priority = 80, name = "High")

        private fun decode(
            id: String,
            displayName: String,
            melody: Boolean,
            priority: Int,
            name: String?,
        ): LoadedDeviceDefinition {
            val melodySection = if (!melody) "" else
                """,
                "melody": {
                  "support": { "name": "$name", "brand": "Sony" },
                  "panel": { "sectionTitle": "BtRemix" }
                }"""
            return DefinitionJsonCodec.decode(
                """
                {
                  "manifest": {
                    "id": "$id",
                    "displayName": "$displayName",
                    "version": "1.0.0",
                    "schemaVersion": 4,
                    "matchers": [{ "type": "namePrefix", "value": "WF-1000XM3", "priority": $priority }]
                  }$melodySection
                }
                """.trimIndent(),
            )
        }
    }

    /** Only [bondedDevices] is exercised here; connecting is the SPP session suite's job. */
    private class FakeRfcommManager : RfcommManager {
        override suspend fun bondedDevices(): List<ClassicDevice> = emptyList()

        override suspend fun connect(address: String, serviceUuid: UUID): RfcommConnection =
            error("not used in this test")

        override suspend fun close() = Unit
    }
}

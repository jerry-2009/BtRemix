package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.definition.loader.BuiltInDefinitionSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Covers the M2.0 boot sequence: one shared registry, an observable ready flag, failures isolated. */
class DevicePackageBootstrapTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun load_registersBuiltInsAndMarksReady() {
        val manager = manager()
        val bootstrap = DevicePackageBootstrap(manager, StringSource(mapOf("demo.json" to definitionJson(id = "demo.builtin"))))

        assertFalse(bootstrap.ready.value)
        val failures = bootstrap.load()

        assertTrue(failures.isEmpty())
        assertTrue(bootstrap.ready.value)
        assertEquals(listOf("demo.builtin"), manager.registry.all().map { it.packageId })
        assertTrue(manager.registry.all().single().isBuiltIn)
    }

    @Test
    fun load_reportsInvalidBuiltInAndKeepsGoing() {
        val manager = manager()
        val bootstrap = DevicePackageBootstrap(
            manager,
            StringSource(
                mapOf(
                    "broken.json" to "{ this is not valid JSON",
                    "demo.json" to definitionJson(id = "demo.builtin"),
                ),
            ),
        )

        val failures = bootstrap.load()

        assertEquals(1, failures.size)
        assertEquals("broken.json", failures.single().sourceName)
        assertEquals(listOf("demo.builtin"), manager.registry.all().map { it.packageId })
    }

    @Test
    fun load_registersInstalledPackagesAndSkipsCorruptFiles() {
        val directory = File(tempFolder.root, "device-packages")
        val manager = DevicePackageManager(DevicePackageStore(directory))
        val bootstrap = DevicePackageBootstrap(manager, StringSource(mapOf("demo.json" to definitionJson(id = "demo.builtin"))))
        manager.install(validPackageBytes(id = "vendor.device"), "vendor-device.dcpkg")
        File(directory, "corrupt.dcpkg").writeBytes("this is not a zip".toByteArray())

        val failures = bootstrap.load()

        assertEquals(
            setOf("demo.builtin", "vendor.device"),
            manager.registry.all().map { it.packageId }.toSet(),
        )
        assertEquals(1, failures.size)
        assertTrue(failures.single().error is PackageError.InvalidZip)
    }

    @Test
    fun loadIfNeeded_doesNotReloadAfterTheFirstLoad() {
        val source = StringSource(mapOf("demo.json" to definitionJson(id = "demo.builtin")))
        val manager = manager()
        val bootstrap = DevicePackageBootstrap(manager, source)
        bootstrap.load()

        source.documents["second.json"] = definitionJson(id = "demo.second")
        bootstrap.loadIfNeeded()

        assertEquals(listOf("demo.builtin"), manager.registry.all().map { it.packageId })
    }

    @Test
    fun reload_picksUpNewBuiltIns() {
        val source = StringSource(mapOf("demo.json" to definitionJson(id = "demo.builtin")))
        val manager = manager()
        val bootstrap = DevicePackageBootstrap(manager, source)
        bootstrap.load()

        source.documents["second.json"] = definitionJson(id = "demo.second")
        bootstrap.load()

        assertEquals(listOf("demo.builtin", "demo.second"), manager.registry.all().map { it.packageId })
    }

    private fun manager(): DevicePackageManager =
        DevicePackageManager(DevicePackageStore(File(tempFolder.root, "device-packages")))

    private class StringSource(documents: Map<String, String>) : BuiltInDefinitionSource {
        val documents: MutableMap<String, String> = documents.toMutableMap()

        override fun list(): List<String> = documents.keys.sorted()

        override fun read(name: String): String = documents.getValue(name)
    }
}

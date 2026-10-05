package com.fusion.melodyLinkNeo.definition.packages

import com.fusion.melodyLinkNeo.core.bluetooth.api.BleDevice
import com.fusion.melodyLinkNeo.core.bluetooth.api.BleScanResult
import com.fusion.melodyLinkNeo.definition.json.DefinitionJsonCodec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DevicePackageRegistryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scanner = DevicePackageReader()

    @Test
    fun registerThenFind() {
        val registry = DevicePackageRegistry()
        registry.register(packageFor("vendor.device"))

        assertEquals(1, registry.all().size)
        assertEquals("vendor.device", registry.find("vendor.device")?.packageId)
        assertNull(registry.find("missing.device"))
    }

    @Test
    fun duplicateIdIsRejectedWithoutReplace() {
        val registry = DevicePackageRegistry()
        registry.register(packageFor("vendor.device"))

        val error = assertPackageError { registry.register(packageFor("vendor.device")) }
        assertTrue(error is PackageError.PackageConflict)
        assertEquals(1, registry.all().size)
    }

    @Test
    fun replaceWithAllowReplaceKeepsSingleEntry() {
        val registry = DevicePackageRegistry()
        registry.register(packageFor("vendor.device", version = "1.0.0"))
        registry.register(packageFor("vendor.device", version = "2.0.0"), allowReplace = true)

        assertEquals(1, registry.all().size)
        assertEquals("2.0.0", registry.find("vendor.device")?.version)
    }

    @Test
    fun builtInCannotBeReplacedByInstalled() {
        val registry = DevicePackageRegistry()
        registry.register(packageFor("vendor.device", source = DevicePackageSource.BUILT_IN))

        val error = assertPackageError {
            registry.register(packageFor("vendor.device"), allowReplace = true)
        }
        assertTrue(error is PackageError.PackageConflict)
        assertTrue(registry.find("vendor.device")!!.isBuiltIn)
    }

    @Test
    fun builtInCannotBeRemoved() {
        val registry = DevicePackageRegistry()
        registry.register(packageFor("vendor.device", source = DevicePackageSource.BUILT_IN))

        val error = assertPackageError { registry.remove("vendor.device") }
        assertTrue(error is PackageError.PackageConflict)
    }

    @Test
    fun removeInstalledStopsReturning() {
        val registry = DevicePackageRegistry()
        registry.register(packageFor("vendor.device"))

        assertTrue(registry.remove("vendor.device"))
        assertNull(registry.find("vendor.device"))
        assertEquals(0, registry.all().size)
    }

    @Test
    fun findMatchUsesDefinitionMatcher() {
        val registry = DevicePackageRegistry()
        registry.register(packageFor("vendor.device"))

        val match = registry.findMatch(scan(name = "Vendor Buds"))
        assertNotNull(match)
        assertEquals("vendor.device", match?.packageId)
        assertNull(registry.findMatch(scan(name = "Unknown")))
    }

    @Test
    fun replaceInstalledPreservesBuiltInsAndDropsMissing() {
        val registry = DevicePackageRegistry()
        registry.register(packageFor("builtin.device", source = DevicePackageSource.BUILT_IN))
        registry.register(packageFor("vendor.device"))

        registry.replaceInstalled(listOf(packageFor("other.device")))

        val ids = registry.all().map { it.packageId }
        assertEquals(listOf("builtin.device", "other.device"), ids)
    }

    @Test
    fun replaceInstalledDropsPackagesConflictingWithBuiltIns() {
        val registry = DevicePackageRegistry()
        registry.register(packageFor("vendor.device", source = DevicePackageSource.BUILT_IN))

        registry.replaceInstalled(listOf(packageFor("vendor.device")))

        assertEquals(1, registry.all().size)
        assertTrue(registry.find("vendor.device")!!.isBuiltIn)
    }

    @Test
    fun managerInstallReportsConflictThenReplaces() {
        val manager = manager()
        val bytes = validPackageBytes(id = "vendor.device", version = "1.0.0")

        val first = manager.install(bytes, "vendor-device.dcpkg")
        assertTrue(first is PackageInstallResult.Installed)

        val second = manager.install(bytes, "vendor-device.dcpkg")
        assertTrue(second is PackageInstallResult.Conflict)

        val replaced = manager.install(bytes, "vendor-device-2.dcpkg", replace = true)
        assertTrue(replaced is PackageInstallResult.Installed)
        assertEquals(1, manager.packages.value.count { !it.isBuiltIn })
    }

    @Test
    fun managerCannotInstallOverBuiltIn() {
        val manager = manager()
        manager.registerBuiltIns(listOf(DefinitionJsonCodec.decode(definitionJson(id = "vendor.device"))))

        val error = assertPackageError { manager.install(validPackageBytes(id = "vendor.device"), "vendor-device.dcpkg") }
        assertTrue(error is PackageError.PackageConflict)
    }

    @Test
    fun managerBuiltInRegistrationIsIdempotent() {
        val manager = manager()
        val definition = DefinitionJsonCodec.decode(definitionJson(id = "vendor.device"))

        assertTrue(manager.registerBuiltIns(listOf(definition)).isEmpty())
        assertTrue(manager.registerBuiltIns(listOf(definition)).isEmpty())
        assertEquals(1, manager.packages.value.size)
        assertTrue(manager.packages.value.single().isBuiltIn)
    }

    @Test
    fun managerRefreshSkipsCorruptPackageAndKeepsGoodOne() {
        val manager = manager()
        val good = manager.storeDirectory().resolve("vendor.device.dcpkg")
        good.parentFile?.mkdirs()
        good.writeBytes(validPackageBytes(id = "vendor.device"))
        File(manager.storeDirectory(), "broken.device.dcpkg").writeBytes("this is not a zip".toByteArray())

        val errors = manager.refreshInstalled()

        assertEquals(listOf("vendor.device"), manager.packages.value.map { it.packageId })
        assertEquals(1, errors.size)
    }

    @Test
    fun managerUninstallRemovesFileAndRegistration() {
        val manager = manager()
        manager.install(validPackageBytes(id = "vendor.device"), "vendor-device.dcpkg")

        assertTrue(manager.uninstall("vendor.device"))
        assertEquals(0, manager.packages.value.size)
        assertTrue(!manager.storeDirectory().resolve("vendor.device.dcpkg").exists())
    }

    @Test
    fun readerProducesValidatedPackageFromBytes() {
        val manager = manager()
        val loaded = manager.load(validPackageBytes(), "vendor-device.dcpkg")
        assertEquals("vendor.device", loaded.packageId)
    }

    private fun manager(): DevicePackageManager =
        DevicePackageManager(DevicePackageStore(File(tempFolder.root, "device-packages")))

    private fun DevicePackageManager.storeDirectory(): File =
        File(tempFolder.root, "device-packages")

    private fun packageFor(
        id: String,
        version: String = "1.0.0",
        source: DevicePackageSource = DevicePackageSource.INSTALLED,
    ): DevicePackage = DevicePackage(
        packageId = id,
        version = version,
        sourceName = "$id.dcpkg",
        definition = DefinitionJsonCodec.decode(definitionJson(id = id, version = version)),
        source = source,
    )

    private fun scan(name: String) = BleScanResult(BleDevice("AA:BB:CC", name), rssi = -40)

    private fun assertPackageError(block: () -> Unit): PackageError {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected PackageException but was $thrown", thrown is PackageException)
        return (thrown as PackageException).error
    }
}

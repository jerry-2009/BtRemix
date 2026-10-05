package com.fusion.melodyLinkNeo.definition.packages

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePackageTest {
    private val reader = DevicePackageReader()
    private val validator = DevicePackageValidator()

    @Test
    fun validPackage_loadsDefinitionAndAssets() {
        val bytes = validPackageBytes(
            id = "vendor.device",
            extra = listOf("assets/icon.png" to byteArrayOf(1, 2, 3)),
        )

        val raw = reader.read(bytes, "vendor-device.dcpkg")
        val packageLoaded = validator.validate(raw)

        assertEquals("vendor.device", packageLoaded.packageId)
        assertEquals("1.0.0", packageLoaded.version)
        assertEquals("Vendor Device", packageLoaded.displayName)
        assertEquals(listOf("assets/icon.png"), packageLoaded.assets)
        assertEquals(DevicePackageSource.INSTALLED, packageLoaded.source)
        assertEquals(1, packageLoaded.matcherCount)
    }

    @Test
    fun missingPackageJson_isRejected() {
        val bytes = storedZip("definition.json" to definitionJson().toByteArray())

        val error = assertPackageError { reader.read(bytes, "broken.dcpkg") }
        assertTrue(error is PackageError.MissingEntry)
    }

    @Test
    fun missingEntryFile_isRejected() {
        val bytes = storedZip("package.json" to packageJson(entry = "definition.json").toByteArray())

        val error = assertPackageError { reader.read(bytes, "broken.dcpkg") }
        assertTrue(error is PackageError.MissingEntry)
        assertEquals("definition.json", (error as PackageError.MissingEntry).entryName)
    }

    @Test
    fun entryPathTraversal_isRejected() {
        val bytes = storedZip(
            "package.json" to packageJson(entry = "../evil.json").toByteArray(),
            "evil.json" to definitionJson().toByteArray(),
        )

        val error = assertPackageError { reader.read(bytes, "traversal.dcpkg") }
        assertTrue(error is PackageError.UnsafePath)
    }

    @Test
    fun zipEntryPathTraversal_isRejected() {
        val bytes = storedZip(
            "package.json" to packageJson().toByteArray(),
            "../evil.json" to definitionJson().toByteArray(),
        )

        val error = assertPackageError { reader.read(bytes, "traversal.dcpkg") }
        assertTrue(error is PackageError.UnsafePath)
    }

    @Test
    fun duplicateEntries_areRejected() {
        val bytes = storedZip(
            "package.json" to packageJson().toByteArray(),
            "definition.json" to definitionJson().toByteArray(),
            "definition.json" to definitionJson().toByteArray(),
        )

        val error = assertPackageError { reader.read(bytes, "duplicate.dcpkg") }
        assertTrue(error is PackageError.UnsupportedFormat)
    }

    @Test
    fun executableContent_isRejected() {
        val bytes = validPackageBytes(extra = listOf("assets/payload.dex" to byteArrayOf(0x64, 0x65, 0x78)))

        val error = assertPackageError { reader.read(bytes, "executable.dcpkg") }
        assertTrue(error is PackageError.UnsupportedFormat)
    }

    @Test
    fun oversizedSingleFile_isRejected() {
        val limited = DevicePackageReader(PackageLimits(maxSingleFileBytes = 16))
        val bytes = validPackageBytes(extra = listOf("assets/big.bin" to ByteArray(64)))

        val error = assertPackageError { limited.read(bytes, "big.dcpkg") }
        assertTrue(error is PackageError.SizeLimitExceeded)
    }

    @Test
    fun tooManyEntries_isRejected() {
        val limited = DevicePackageReader(PackageLimits(maxEntries = 1))

        val error = assertPackageError { limited.read(validPackageBytes(), "many.dcpkg") }
        assertTrue(error is PackageError.SizeLimitExceeded)
    }

    @Test
    fun oversizedArchive_isRejected() {
        val limited = DevicePackageReader(PackageLimits(maxArchiveBytes = 8))

        val error = assertPackageError { limited.read(validPackageBytes(), "big.dcpkg") }
        assertTrue(error is PackageError.SizeLimitExceeded)
    }

    @Test
    fun nonZipBytes_areRejected() {
        val error = assertPackageError { reader.read("not a zip".toByteArray(), "text.dcpkg") }
        assertTrue(error is PackageError.InvalidZip)
    }

    @Test
    fun readingFromFile_works() {
        val file = File.createTempFile("vendor", ".dcpkg")
        try {
            file.writeBytes(validPackageBytes())
            val packageLoaded = validator.validate(reader.read(file))
            assertEquals("vendor.device", packageLoaded.packageId)
        } finally {
            file.delete()
        }
    }

    private fun assertPackageError(block: () -> Unit): PackageError {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected PackageException but was $thrown", thrown is PackageException)
        return (thrown as PackageException).error
    }
}

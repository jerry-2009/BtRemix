package com.fusion.melodyLinkNeo.definition.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePackageValidatorTest {
    private val reader = DevicePackageReader()
    private val validator = DevicePackageValidator()

    @Test
    fun unsupportedPackageFormat_isRejected() {
        val raw = reader.read(validPackageBytes()) // reader does not care about the format

        val error = assertPackageError {
            validator.validate(raw.copy(packageJson = packageJson(packageFormat = 99)))
        }
        assertTrue(error is PackageError.UnsupportedFormat)
        assertEquals("package.json.packageFormat", error.path)
    }

    @Test
    fun packageIdMismatch_isRejected() {
        val raw = reader.read(validPackageBytes(id = "vendor.device", version = "1.0.0"))

        val error = assertPackageError {
            validator.validate(raw.copy(packageJson = packageJson(id = "other.device", version = "1.0.0")))
        }
        assertTrue(error is PackageError.MetadataMismatch)
        assertEquals("manifest.id", error.path)
    }

    @Test
    fun packageVersionMismatch_isRejected() {
        val raw = reader.read(validPackageBytes(id = "vendor.device", version = "1.0.0"))

        val error = assertPackageError {
            validator.validate(raw.copy(packageJson = packageJson(id = "vendor.device", version = "2.0.0")))
        }
        assertTrue(error is PackageError.MetadataMismatch)
        assertEquals("manifest.version", error.path)
    }

    @Test
    fun invalidDefinition_keepsValidatorPath() {
        val invalidDefinition = """
            {"manifest":{"id":"vendor.device","displayName":"Vendor Device","version":"1.0.0",
             "matchers":[{"type":"namePrefix","value":"Vendor"}]},
             "states":{"battery":{"type":"integer"}},
             "ui":{"children":[{"type":"value","state":"missing"}]}}
        """.trimIndent()
        val raw = reader.read(validPackageBytes()).copy(definitionJson = invalidDefinition)

        val error = assertPackageError { validator.validate(raw) }
        assertTrue(error is PackageError.DefinitionInvalid)
        assertEquals("ui.children[0].state", (error as PackageError.DefinitionInvalid).path)
    }

    @Test
    fun definitionJsonSyntaxError_isRejected() {
        val raw = reader.read(validPackageBytes()).copy(definitionJson = "{ not json")

        val error = assertPackageError { validator.validate(raw) }
        assertTrue(error is PackageError.DefinitionInvalid)
    }

    @Test
    fun builtInSource_isPreserved() {
        val raw = reader.read(validPackageBytes())
        val packageLoaded = validator.validate(raw, DevicePackageSource.BUILT_IN)
        assertTrue(packageLoaded.isBuiltIn)
    }

    private fun assertPackageError(block: () -> Unit): PackageError {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected PackageException but was $thrown", thrown is PackageException)
        return (thrown as PackageException).error
    }
}

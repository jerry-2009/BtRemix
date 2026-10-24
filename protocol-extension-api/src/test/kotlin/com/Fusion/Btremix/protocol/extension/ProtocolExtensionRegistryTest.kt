package com.Fusion.Btremix.protocol.extension

import kotlin.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolExtensionRegistryTest {
    @Test
    fun registration_isExplicitAndOrderIndependent() {
        val first = extension("test.first")
        val second = extension("test.second")
        val forward = ProtocolExtensionRegistry(listOf(first, second))
        val reverse = ProtocolExtensionRegistry(listOf(second, first))

        assertSame(first, forward.find("test.first"))
        assertSame(second, forward.find("test.second"))
        assertEquals(forward.all().map { it.id }, reverse.all().map { it.id })
    }

    @Test
    fun duplicateId_neverOverwritesTheFirstImplementation() {
        val registry = ProtocolExtensionRegistry(listOf(extension("test.duplicate")))

        assertThrows(IllegalArgumentException::class.java) {
            registry.register(extension("test.duplicate"))
        }
        assertEquals(1, registry.size)
    }

    @Test
    fun blankAndFutureVersions_areRejectedBeforeCreation() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolExtensionRegistry(listOf(extension(" ")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolExtensionRegistry(listOf(extension("test.future", apiVersion = 2)))
        }
    }

    @Test
    fun missingRegistration_isDistinctFromAnEmptyRegistryEntry() {
        val registry = ProtocolExtensionRegistry()

        assertNull(registry.find("test.missing"))
        assertEquals(0, registry.size)
    }

    private fun extension(id: String, apiVersion: Int = 1): ProtocolExtension = object : ProtocolExtension {
        override val id = id
        override val apiVersion = apiVersion
        override val entries = setOf("default")

        override fun create(runtime: ExtensionRuntime): ProtocolExtensionInstance =
            object : ProtocolExtensionInstance {
                override suspend fun execute(
                    entry: String,
                    input: ByteArray,
                    io: ControlledProtocolIo,
                ): ExtensionResult = ExtensionResult.Success(input)
            }
    }
}

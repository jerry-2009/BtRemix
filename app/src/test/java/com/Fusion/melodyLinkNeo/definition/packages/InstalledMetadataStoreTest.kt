package com.fusion.melodyLinkNeo.definition.packages

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3.5C: the local enable/install metadata round-trips through a plain JSON file. */
class InstalledMetadataStoreTest {

    @Test
    fun missingFile_readsAsEmpty() {
        val dir = Files.createTempDirectory("meta").toFile()
        val store = InstalledMetadataStore(java.io.File(dir, InstalledMetadataStore.FILE_NAME))
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun roundTrip_keepsEveryField() {
        val dir = Files.createTempDirectory("meta").toFile()
        val store = InstalledMetadataStore(java.io.File(dir, InstalledMetadataStore.FILE_NAME))
        val metadata = mapOf(
            "sony.wf1000xm3" to InstalledPackageMetadata(
                packageId = "sony.wf1000xm3",
                enabled = false,
                installedAt = 1_759_000_000_000,
                source = "file:/tmp/x.dcpkg",
                updateSource = "https://example.invalid/index.json",
                pinnedVersion = "1.0.3",
                lastCheckedAt = 1_759_100_000_000,
            ),
            "demo.fusion" to InstalledPackageMetadata(packageId = "demo.fusion", enabled = true),
        )

        store.write(metadata)
        val restored = store.read()

        assertEquals(2, restored.size)
        val sony = restored.getValue("sony.wf1000xm3")
        assertFalse(sony.enabled)
        assertEquals(1_759_000_000_000, sony.installedAt)
        assertEquals("file:/tmp/x.dcpkg", sony.source)
        assertEquals("https://example.invalid/index.json", sony.updateSource)
        assertTrue(restored.getValue("demo.fusion").enabled)
        assertNull(restored.getValue("demo.fusion").installedAt)
    }

    @Test
    fun corruptFile_readsAsEmptyInsteadOfThrowing() {
        val dir = Files.createTempDirectory("meta").toFile()
        val file = java.io.File(dir, InstalledMetadataStore.FILE_NAME)
        file.writeText("{ not json")
        assertTrue(InstalledMetadataStore(file).read().isEmpty())
    }
}

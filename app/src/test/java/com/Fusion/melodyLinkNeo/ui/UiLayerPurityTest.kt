package com.fusion.melodyLinkNeo.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-UI-4 / §3.2: the product UI may not depend on transport, protocol or hook internals, and may not
 * hard-code a vendor name.
 *
 * A source scan is delegated to below the compiler so the rule keeps holding as the page count grows;
 * the developer tools (ui/devtools, ui/explorer, ui/studio, ui/melody, ui/packages) are explicitly
 * out of scope because they are debugging surfaces.
 */
class UiLayerPurityTest {

    private val productPackages = listOf(
        "components",
        "home",
        "devices",
        "definitions",
        "settings",
        "shell",
        "theme",
        "renderer",
    )

    private val forbiddenImports = listOf(
        "import android.bluetooth",
        "import com.fusion.melodyLinkNeo.core.bluetooth",
        "import com.fusion.melodyLinkNeo.protocol",
        "import com.fusion.melodyLinkNeo.melody.",
        "import com.fusion.melodyLinkNeo.melody\n",
    )

    /** Vendor names that must not appear in product UI code; device names come from packages. */
    private val vendorNames = listOf("Sony", "Huawei", "Xiaomi", "OPPO", "OnePlus", "Enco")

    @Test
    fun productUiDoesNotImportTransportOrHookInternals() {
        val violations = mutableListOf<String>()
        productFiles().forEach { file ->
            val text = file.readText()
            forbiddenImports.forEach { forbidden ->
                if (text.contains(forbidden)) violations += "${file.relativeToOrNull(uiRoot())}: $forbidden"
            }
        }
        assertTrue("forbidden imports in product UI:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun productUiHasNoVendorHardCoding() {
        val violations = mutableListOf<String>()
        productFiles().forEach { file ->
            val text = file.readText()
            vendorNames.forEach { vendor ->
                if (text.contains(vendor)) violations += "${file.relativeToOrNull(uiRoot())}: $vendor"
            }
        }
        assertTrue("vendor names in product UI:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun productFiles(): List<File> = productPackages
        .map { File(uiRoot(), it) }
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }

    private fun uiRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/fusion/melodyLinkNeo/ui"),
            File("app/src/main/java/com/fusion/melodyLinkNeo/ui"),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate the ui source root from ${File("").absolutePath}")
    }

    private fun File.relativeToOrNull(base: File): String = runCatching { relativeTo(base).path }.getOrDefault(path)
}

package com.Fusion.Btremix.protocol.extension

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4 architecture guard: extension modules stay pure and packages never load executable code. */
class ProtocolExtensionBoundaryTest {
    @Test
    fun apiAndReferenceModulesDependOnlyOnThePureExtensionBoundary() {
        val forbiddenImports = listOf(
            "import android.",
            "import com.Fusion.Btremix.core.",
            "import com.Fusion.Btremix.definition.",
            "import com.Fusion.Btremix.device.",
            "import com.Fusion.Btremix.melody.",
            "import com.Fusion.Btremix.scripting.",
            "import com.Fusion.Btremix.ui.",
            "import com.Fusion.Btremix.BtRemixApplication",
        )
        val violations = mutableListOf<String>()
        extensionMainFiles().forEach { file ->
            val text = file.readText()
            forbiddenImports.filter(text::contains).forEach { forbidden ->
                violations += "${file.path}: $forbidden"
            }
        }

        assertTrue("pure extension modules import host internals:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun definitionLoadingContainsNoDynamicClassOrScriptLoader() {
        val forbidden = listOf(
            "Class.forName",
            "loadClass(",
            "DexClassLoader",
            "PathClassLoader",
            "ServiceLoader.load",
            "defineClass(",
        )
        val violations = mutableListOf<String>()
        definitionMainFiles().forEach { file ->
            val text = file.readText()
            forbidden.filter(text::contains).forEach { token ->
                violations += "${file.path}: $token"
            }
        }

        assertTrue("Definition loading can execute package code:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun extensionMainFiles(): List<File> = listOf(
        repositoryFile("protocol-extension-api/src/main"),
        repositoryFile("vendor/testbed/src/main"),
    ).flatMap(::kotlinFiles)

    private fun definitionMainFiles(): List<File> =
        kotlinFiles(repositoryFile("app/src/main/java/com/Fusion/Btremix/definition"))

    private fun kotlinFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun repositoryFile(path: String): File = listOf(
        File(path),
        File("../$path"),
        File("../../BtRemix/$path"),
    ).firstOrNull(File::exists) ?: error("Cannot locate repository path '$path' from ${File("").absolutePath}")
}

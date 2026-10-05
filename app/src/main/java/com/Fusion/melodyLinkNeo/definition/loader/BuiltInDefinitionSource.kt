package com.fusion.melodyLinkNeo.definition.loader

import android.content.res.AssetManager

/**
 * Supplies the Definition documents that ship inside the APK.
 *
 * The abstraction keeps [com.fusion.melodyLinkNeo.definition.packages.DevicePackageBootstrap] free of
 * Android so the boot sequence can be exercised from JVM tests.
 */
interface BuiltInDefinitionSource {
    /** Definition document names, in load order. */
    fun list(): List<String>

    /** Reads one document by the name returned from [list]. */
    fun read(name: String): String
}

/** Reads built-in definitions from a directory inside the APK assets. */
class AndroidAssetBuiltInDefinitionSource(
    private val assets: AssetManager,
    private val directory: String = "definitions",
) : BuiltInDefinitionSource {
    override fun list(): List<String> = assets.list(directory)
        ?.filter { it.endsWith(".json", ignoreCase = true) }
        ?.sorted()
        ?: emptyList()

    override fun read(name: String): String = assets.open("$directory/$name").bufferedReader().use { it.readText() }
}

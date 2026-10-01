package com.Fusion.Btremix.definition.loader

import android.content.res.AssetManager

class AndroidAssetDefinitionSource(private val assets: AssetManager) : DefinitionSource {
    override fun read(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    fun paths(directory: String = "definitions"): List<String> = assets.list(directory)
        ?.filter { it.endsWith(".json", ignoreCase = true) }
        ?.sorted()
        ?: emptyList()
}

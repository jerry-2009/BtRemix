package com.Fusion.Btremix.definition.loader

import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import java.io.File

interface DefinitionSource {
    fun read(path: String): String
}

class StringDefinitionSource(private val json: String) : DefinitionSource {
    override fun read(path: String): String = json
}

class DirectoryDefinitionSource(private val directory: File) : DefinitionSource {
    override fun read(path: String): String = File(directory, path).readText()

    fun paths(): List<String> = directory.listFiles()
        ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        ?.sortedBy { it.name }
        ?.map { it.name }
        ?: emptyList()
}

class DefinitionLoader {
    fun load(json: String): LoadedDeviceDefinition = DefinitionJsonCodec.decode(json)

    fun load(source: DefinitionSource, path: String): LoadedDeviceDefinition = load(source.read(path))

    fun loadDirectory(source: DirectoryDefinitionSource): List<LoadedDeviceDefinition> =
        source.paths().map { load(source, it) }

    fun loadAssets(source: AndroidAssetDefinitionSource, directory: String = "definitions"): List<LoadedDeviceDefinition> =
        source.paths(directory).map { load(source, "$directory/$it") }
}

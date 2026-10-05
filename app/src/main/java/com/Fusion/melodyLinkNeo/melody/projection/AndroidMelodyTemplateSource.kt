package com.fusion.melodyLinkNeo.melody.projection

import android.content.res.AssetManager

/**
 * Reads the whitelist template from the APK assets.
 *
 * Definitions reference it as `assets/melody/whitelist-template.json` (the `assets/` prefix reads
 * naturally in a `.dcpkg`), while `AssetManager` paths are relative to `assets/`. M3 only supports
 * APK assets, not templates carried inside a package (M3-D4, known limitation), so this is the whole
 * resolution strategy for now.
 */
class AndroidMelodyTemplateSource(private val assets: AssetManager) : MelodyTemplateSource {
    override fun read(path: String): String? = runCatching {
        assets.open(assetPath(path)).bufferedReader().use { it.readText() }
    }.getOrNull()

    companion object {
        /** Maps a Definition-declared path to an `AssetManager` path. */
        fun assetPath(path: String): String =
            path.trim().trimStart('/').removePrefix("assets/")
    }
}

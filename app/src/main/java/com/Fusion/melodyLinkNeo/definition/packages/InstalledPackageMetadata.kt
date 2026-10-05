package com.fusion.melodyLinkNeo.definition.packages

import com.fusion.melodyLinkNeo.definition.json.JsonParser
import com.fusion.melodyLinkNeo.definition.json.JsonValue
import com.fusion.melodyLinkNeo.definition.json.JsonWriter
import java.io.File

/**
 * Local, per-package state that does not belong inside a `.dcpkg` (DEVICE_CENTER_UI_PLAN §3.5C).
 *
 * A package archive is immutable content; whether it is enabled, when it was installed and where it
 * came from are properties of *this device's* installation, so they live in a JSON file next to the
 * `.dcpkg` files. Built-in packages can be disabled but never uninstalled.
 */
data class InstalledPackageMetadata(
    val packageId: String,
    val enabled: Boolean = true,
    val installedAt: Long? = null,
    val source: String? = null,
    val updateSource: String? = null,
    val pinnedVersion: String? = null,
    val lastCheckedAt: Long? = null,
)

/** Reads/writes the whole metadata map as one small JSON document. */
class InstalledMetadataStore(private val file: File) {

    fun read(): Map<String, InstalledPackageMetadata> {
        if (!file.isFile) return emptyMap()
        val text = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return emptyMap()
        val root = runCatching { JsonParser.parse(text) as? JsonValue.Object }.getOrNull() ?: return emptyMap()
        val packages = root.values["packages"] as? JsonValue.Array ?: return emptyMap()
        return packages.values.mapNotNull { element ->
            val obj = element as? JsonValue.Object ?: return@mapNotNull null
            val id = obj.string("packageId") ?: return@mapNotNull null
            InstalledPackageMetadata(
                packageId = id,
                enabled = obj.boolean("enabled") ?: true,
                installedAt = obj.long("installedAt"),
                source = obj.string("source"),
                updateSource = obj.string("updateSource"),
                pinnedVersion = obj.string("pinnedVersion"),
                lastCheckedAt = obj.long("lastCheckedAt"),
            )
        }.associateBy { it.packageId }
    }

    fun write(metadata: Map<String, InstalledPackageMetadata>) {
        val document = JsonValue.Object(
            linkedMapOf(
                "schema" to JsonValue.NumberValue("1"),
                "packages" to JsonValue.Array(
                    metadata.values.sortedBy { it.packageId }.map { entry ->
                        JsonValue.Object(
                            linkedMapOf(
                                "packageId" to JsonValue.StringValue(entry.packageId),
                                "enabled" to JsonValue.BooleanValue(entry.enabled),
                                "installedAt" to (entry.installedAt?.let { JsonValue.NumberValue(it.toString()) } ?: JsonValue.NullValue),
                                "source" to (entry.source?.let { JsonValue.StringValue(it) } ?: JsonValue.NullValue),
                                "updateSource" to (entry.updateSource?.let { JsonValue.StringValue(it) } ?: JsonValue.NullValue),
                                "pinnedVersion" to (entry.pinnedVersion?.let { JsonValue.StringValue(it) } ?: JsonValue.NullValue),
                                "lastCheckedAt" to (entry.lastCheckedAt?.let { JsonValue.NumberValue(it.toString()) } ?: JsonValue.NullValue),
                            ),
                        )
                    },
                ),
            ),
        )
        file.parentFile?.mkdirs()
        file.writeText(JsonWriter.write(document))
    }

    private fun JsonValue.Object.string(key: String): String? = (values[key] as? JsonValue.StringValue)?.value

    private fun JsonValue.Object.boolean(key: String): Boolean? = (values[key] as? JsonValue.BooleanValue)?.value

    private fun JsonValue.Object.long(key: String): Long? = (values[key] as? JsonValue.NumberValue)?.raw?.toLongOrNull()

    companion object {
        const val FILE_NAME: String = "installed-metadata.json"
    }
}

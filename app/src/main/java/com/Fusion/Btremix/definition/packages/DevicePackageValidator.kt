package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.json.DefinitionJsonException
import com.Fusion.Btremix.definition.json.JsonParseException
import com.Fusion.Btremix.definition.json.JsonParser
import com.Fusion.Btremix.definition.json.JsonValue
import com.Fusion.Btremix.definition.validator.DefinitionValidationException

/**
 * Validates a [RawDevicePackage]: package structure and metadata first, then the embedded
 * Definition through the existing [DefinitionJsonCodec] and Definition validator.
 *
 * Definition rules are never re-implemented here.
 */
class DevicePackageValidator {

    fun validate(
        raw: RawDevicePackage,
        source: DevicePackageSource = DevicePackageSource.INSTALLED,
    ): DevicePackage {
        val metadata = parseMetadata(raw.packageJson)

        if (metadata.packageFormat !in DevicePackageFormat.SUPPORTED) {
            throw PackageException(
                PackageError.UnsupportedFormat(
                    detail = "unsupported packageFormat ${metadata.packageFormat}, supported ${DevicePackageFormat.SUPPORTED}",
                    path = "package.json.packageFormat",
                ),
            )
        }
        if (!PACKAGE_ID_REGEX.matches(metadata.id)) {
            throw PackageException(
                PackageError.UnsupportedFormat(
                    detail = "id '${metadata.id}' must contain only letters, digits, '.', '_' or '-'",
                    path = "package.json.id",
                ),
            )
        }
        if (metadata.version.isBlank()) {
            throw PackageException(PackageError.UnsupportedFormat("version must not be blank", "package.json.version"))
        }
        requireSafePackagePath(metadata.entry, raw.sourceName)
        if (!metadata.entry.endsWith(".json", ignoreCase = true)) {
            throw PackageException(
                PackageError.UnsupportedFormat("entry must point to a JSON file", "package.json.entry"),
            )
        }
        if (!isAllowedPackageEntry(metadata.entry)) {
            throw PackageException(
                PackageError.UnsupportedFormat("entry is not an allowed package content", "package.json.entry"),
            )
        }
        val definition = decodeDefinition(raw)
        if (metadata.id != definition.manifest.id) {
            throw PackageException(
                PackageError.MetadataMismatch(
                    detail = "package id '${metadata.id}' does not match manifest.id '${definition.manifest.id}'",
                    path = "manifest.id",
                ),
            )
        }
        if (metadata.version != definition.manifest.version) {
            throw PackageException(
                PackageError.MetadataMismatch(
                    detail = "package version '${metadata.version}' does not match manifest.version '${definition.manifest.version}'",
                    path = "manifest.version",
                ),
            )
        }

        return DevicePackage(
            packageId = metadata.id,
            version = metadata.version,
            sourceName = raw.sourceName,
            definition = definition,
            assets = raw.assets,
            source = source,
            packageFormat = metadata.packageFormat,
        )
    }

    private fun decodeDefinition(raw: RawDevicePackage): LoadedDeviceDefinition = try {
        DefinitionJsonCodec.decode(raw.definitionJson, validate = true)
    } catch (error: DefinitionValidationException) {
        val first = error.errors.firstOrNull()
        throw PackageException(
            PackageError.DefinitionInvalid(
                path = first?.path ?: "definition",
                detail = error.errors.joinToString("; ") { "${it.path}: ${it.message}" },
            ),
        )
    } catch (error: DefinitionJsonException) {
        throw PackageException(PackageError.DefinitionInvalid(error.path, error.message ?: "invalid definition"))
    } catch (error: JsonParseException) {
        throw PackageException(PackageError.DefinitionInvalid("definition", error.message ?: "invalid JSON"))
    } catch (error: IllegalArgumentException) {
        throw PackageException(PackageError.DefinitionInvalid("definition", error.message ?: "invalid definition"))
    }

    private fun parseMetadata(packageJson: String): PackageMetadata {
        val root = try {
            JsonParser.parse(packageJson)
        } catch (error: JsonParseException) {
            throw PackageException(
                PackageError.UnsupportedFormat("package.json is not valid JSON: ${error.message}", "package.json"),
            )
        }
        val obj = root as? JsonValue.Object
            ?: throw PackageException(PackageError.UnsupportedFormat("package.json must be a JSON object", "package.json"))
        val format = (obj.values["packageFormat"] as? JsonValue.NumberValue)?.raw?.toIntOrNull()
            ?: throw PackageException(
                PackageError.UnsupportedFormat("packageFormat is required and must be an integer", "package.json.packageFormat"),
            )
        val id = (obj.values["id"] as? JsonValue.StringValue)?.value
            ?: throw PackageException(PackageError.UnsupportedFormat("id is required", "package.json.id"))
        val version = (obj.values["version"] as? JsonValue.StringValue)?.value
            ?: throw PackageException(PackageError.UnsupportedFormat("version is required", "package.json.version"))
        val entry = (obj.values["entry"] as? JsonValue.StringValue)?.value
            ?: throw PackageException(PackageError.UnsupportedFormat("entry is required", "package.json.entry"))
        return PackageMetadata(format, id, version, entry)
    }

    private data class PackageMetadata(
        val packageFormat: Int,
        val id: String,
        val version: String,
        val entry: String,
    )
}

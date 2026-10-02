package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.definition.json.JsonParseException
import com.Fusion.Btremix.definition.json.JsonParser
import com.Fusion.Btremix.definition.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Reads a `.dcpkg` (ZIP container) into an in-memory [RawDevicePackage].
 *
 * The reader never trusts archive paths: it streams entries into memory, rejecting unsafe names,
 * duplicate entries, disallowed content and anything exceeding [PackageLimits]. It does not
 * register packages and does not validate Definition semantics - that is
 * [DevicePackageValidator]'s job.
 */
class DevicePackageReader(private val limits: PackageLimits = PackageLimits()) {

    fun read(bytes: ByteArray, sourceName: String = "package.dcpkg"): RawDevicePackage {
        if (bytes.size.toLong() > limits.maxArchiveBytes) {
            throw PackageException(
                PackageError.SizeLimitExceeded(
                    detail = "archive is ${bytes.size} bytes, limit is ${limits.maxArchiveBytes} bytes",
                    path = sourceName,
                ),
            )
        }
        return read(bytes.inputStream(), sourceName, bytes.size.toLong())
    }

    fun read(input: InputStream, sourceName: String = "package.dcpkg"): RawDevicePackage =
        read(input, sourceName, null)

    fun read(file: File): RawDevicePackage {
        if (!file.isFile) {
            throw PackageException(PackageError.MissingEntry(file.path, path = file.name))
        }
        if (file.length() > limits.maxArchiveBytes) {
            throw PackageException(
                PackageError.SizeLimitExceeded(
                    detail = "archive is ${file.length()} bytes, limit is ${limits.maxArchiveBytes} bytes",
                    path = file.name,
                ),
            )
        }
        return file.inputStream().use { read(it, file.name, file.length()) }
    }

    private fun read(input: InputStream, sourceName: String, declaredSize: Long?): RawDevicePackage {
        val entries = linkedMapOf<String, ByteArray>()
        val seen = mutableSetOf<String>()
        var totalBytes = 0L
        var entryCount = 0

        try {
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > limits.maxEntries) {
                        throw PackageException(
                            PackageError.SizeLimitExceeded(
                                detail = "archive has more than ${limits.maxEntries} entries",
                                path = sourceName,
                            ),
                        )
                    }
                    if (!entry.isDirectory) {
                        val name = entry.name
                        requireSafePackagePath(name, sourceName)
                        if (!seen.add(name)) {
                            throw PackageException(
                                PackageError.UnsupportedFormat(
                                    detail = "duplicate entry '$name'",
                                    path = name,
                                ),
                            )
                        }
                        if (!isAllowedPackageEntry(name)) {
                            throw PackageException(
                                PackageError.UnsupportedFormat(
                                    detail = "only JSON documents and assets/ resources are allowed: '$name'",
                                    path = name,
                                ),
                            )
                        }
                        val data = readEntry(zip, name, sourceName)
                        totalBytes += data.size
                        if (totalBytes > limits.maxTotalUncompressedBytes) {
                            throw PackageException(
                                PackageError.SizeLimitExceeded(
                                    detail = "uncompressed content exceeds ${limits.maxTotalUncompressedBytes} bytes",
                                    path = sourceName,
                                ),
                            )
                        }
                        entries[name] = data
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (error: PackageException) {
            throw error
        } catch (error: ZipException) {
            throw PackageException(PackageError.InvalidZip(error.message ?: "not a valid ZIP archive", sourceName))
        } catch (error: IOException) {
            throw PackageException(PackageError.InvalidZip(error.message ?: "failed to read archive", sourceName))
        }

        if (entries.isEmpty()) {
            throw PackageException(
                PackageError.InvalidZip(
                    detail = if (declaredSize == 0L) "archive is empty" else "archive contains no readable entries",
                    path = sourceName,
                ),
            )
        }

        val packageJsonBytes = entries["package.json"]
            ?: throw PackageException(PackageError.MissingEntry("package.json", "package.json"))
        val packageJson = String(packageJsonBytes, Charsets.UTF_8)
        val entryPath = readEntryPath(packageJson, sourceName)
        requireSafePackagePath(entryPath, sourceName)
        val definitionBytes = entries[entryPath]
            ?: throw PackageException(PackageError.MissingEntry(entryPath, "package.json.entry"))

        return RawDevicePackage(
            sourceName = sourceName,
            packageJson = packageJson,
            entryPath = entryPath,
            definitionJson = String(definitionBytes, Charsets.UTF_8),
            assets = entries.keys.filter { it.startsWith("assets/") }.sorted(),
        )
    }

    private fun readEntryPath(packageJson: String, sourceName: String): String {
        val root = try {
            JsonParser.parse(packageJson)
        } catch (error: JsonParseException) {
            throw PackageException(
                PackageError.UnsupportedFormat(
                    detail = "package.json is not valid JSON: ${error.message}",
                    path = "package.json",
                ),
            )
        }
        val obj = root as? JsonValue.Object
            ?: throw PackageException(PackageError.UnsupportedFormat("package.json must be a JSON object", "package.json"))
        val entry = (obj.values["entry"] as? JsonValue.StringValue)?.value
            ?: throw PackageException(PackageError.MissingEntry("entry", "package.json.entry"))
        if (entry.isBlank()) {
            throw PackageException(PackageError.MissingEntry("entry", "package.json.entry"))
        }
        return entry
    }

    private fun readEntry(zip: ZipInputStream, name: String, sourceName: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var size = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            size += read
            if (size > limits.maxSingleFileBytes) {
                throw PackageException(
                    PackageError.SizeLimitExceeded(
                        detail = "entry '$name' exceeds ${limits.maxSingleFileBytes} bytes",
                        path = name,
                    ),
                )
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

}

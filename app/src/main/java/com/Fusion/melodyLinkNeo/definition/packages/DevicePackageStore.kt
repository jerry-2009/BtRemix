package com.fusion.melodyLinkNeo.definition.packages

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists installed `.dcpkg` files inside an app-private directory.
 *
 * Writes go to a temporary file first and are then moved into place, so an interrupted or failed
 * install never overwrites a valid package.
 */
class DevicePackageStore(private val directory: File) {

    fun directory(): File = directory

    fun packageFile(packageId: String): File = File(directory, "$packageId.$PACKAGE_EXTENSION")

    fun list(): List<File> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".$PACKAGE_EXTENSION", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun read(file: File): ByteArray = try {
        file.readBytes()
    } catch (error: IOException) {
        throw PackageException(PackageError.StorageFailure("cannot read '${file.name}': ${error.message}"))
    }

    fun exists(packageId: String): Boolean = packageFile(packageId).isFile

    fun write(packageId: String, bytes: ByteArray): File {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw PackageException(PackageError.StorageFailure("cannot create '${directory.path}'"))
            }
            val target = packageFile(packageId)
            val temporary = File(directory, ".${packageId}.${System.nanoTime()}.tmp")
            temporary.writeBytes(bytes)
            moveReplacing(temporary, target)
            return target
        } catch (error: PackageException) {
            throw error
        } catch (error: IOException) {
            throw PackageException(PackageError.StorageFailure("cannot persist '$packageId': ${error.message}"))
        }
    }

    fun delete(packageId: String): Boolean = try {
        val file = packageFile(packageId)
        !file.exists() || file.delete()
    } catch (error: SecurityException) {
        throw PackageException(PackageError.StorageFailure("cannot delete '$packageId': ${error.message}"))
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val PACKAGE_EXTENSION: String = "dcpkg"

        /** Directory under `filesDir` that holds installed `.dcpkg` files. */
        const val DIRECTORY_NAME: String = "device-packages"
    }
}

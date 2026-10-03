package com.Fusion.Btremix.ui.packages

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.Fusion.Btremix.BtRemixApplication
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.packages.DevicePackage
import com.Fusion.Btremix.definition.packages.PackageLoadFailure
import com.Fusion.Btremix.definition.packages.PackageError
import com.Fusion.Btremix.definition.packages.PackageException
import com.Fusion.Btremix.definition.packages.PackageInstallResult
import com.Fusion.Btremix.definition.packages.PackageLimits
import com.Fusion.Btremix.definition.packages.asPackageError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in the developer tools package list. */
data class PackageListItem(
    val packageId: String,
    val displayName: String,
    val version: String,
    val sourceName: String,
    val isBuiltIn: Boolean,
    val capabilities: List<String>,
    val matcherCount: Int,
    val assetCount: Int,
)

/** A package failure paired with the file it came from, ready for display. */
data class PackageErrorItem(
    val sourceName: String?,
    val error: PackageError,
)

/** Asks the user to confirm replacing an already installed package id. */
data class ReplacePrompt(
    val packageId: String,
    val displayName: String,
    val existingVersion: String,
    val candidateVersion: String,
)

/** Renders a registered package's `ui` section without connecting to a device. */
data class PackageUiPreview(
    val packageId: String,
    val displayName: String,
    val version: String,
    val definition: LoadedDeviceDefinition,
)

data class PackageToolsUiState(
    val packages: List<PackageListItem> = emptyList(),
    val loading: Boolean = false,
    val installing: Boolean = false,
    val errors: List<PackageErrorItem> = emptyList(),
    val selectedPackageId: String? = null,
    val replacePrompt: ReplacePrompt? = null,
    val preview: PackageUiPreview? = null,
    val message: String? = null,
)

/**
 * Developer tools state for installing, listing and removing `.dcpkg` device packages.
 *
 * The ViewModel owns the Android-specific parts (file picker URI, cache copy) and delegates package
 * semantics to the process-scoped manager owned by [BtRemixApplication]. Sharing that manager is what
 * lets the BLE Explorer see packages installed from this screen without a reload.
 */
class PackageToolsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BtRemixApplication
    private val bootstrap = app.packageBootstrap
    private val manager = app.packages
    private val mutableState = MutableStateFlow(PackageToolsUiState(loading = true))
    val state: StateFlow<PackageToolsUiState> = mutableState.asStateFlow()

    private var pendingInstallFile: File? = null
    private var pendingSourceName: String? = null

    init {
        viewModelScope.launch {
            manager.packages.collect { packages ->
                mutableState.update { current ->
                    current.copy(packages = packages.map(::toListItem))
                }
            }
        }
        viewModelScope.launch {
            bootstrap.ready.collect { ready -> mutableState.update { it.copy(loading = !ready) } }
        }
        viewModelScope.launch {
            bootstrap.failures.collect { failures ->
                mutableState.update { it.copy(errors = failures.map(::toErrorItem)) }
            }
        }
        viewModelScope.launch { withContext(Dispatchers.IO) { bootstrap.loadIfNeeded() } }
    }

    /** Re-reads built-in definitions and re-scans the private package directory. */
    fun reload() {
        viewModelScope.launch { withContext(Dispatchers.IO) { bootstrap.load() } }
    }

    fun install(uri: Uri) {
        if (mutableState.value.installing) return
        clearPendingInstall()
        viewModelScope.launch {
            mutableState.update { it.copy(installing = true, message = null, replacePrompt = null) }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val file = copyToCache(uri)
                    val sourceName = displayName(uri) ?: file.name
                    commitInstall(file, sourceName, replace = false)
                }.getOrElse { failure ->
                    InstallOutcome.Failed(null, PackageErrorItem(null, failure.asPackageError(uri.lastPathSegment)))
                }
            }
            consume(outcome)
        }
    }

    fun confirmReplace() {
        val file = pendingInstallFile ?: return
        val sourceName = pendingSourceName ?: file.name
        pendingInstallFile = null
        pendingSourceName = null
        viewModelScope.launch {
            mutableState.update { it.copy(installing = true, replacePrompt = null) }
            val outcome = withContext(Dispatchers.IO) { commitInstall(file, sourceName, replace = true) }
            consume(outcome)
        }
    }

    fun cancelReplace() {
        clearPendingInstall()
        mutableState.update { it.copy(replacePrompt = null) }
    }

    fun delete(packageId: String) {
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { manager.uninstall(packageId) }.exceptionOrNull()?.asPackageError(packageId)
            }
            if (failure == null) {
                mutableState.update {
                    it.copy(
                        message = "Removed $packageId",
                        selectedPackageId = null,
                        preview = it.preview?.takeUnless { preview -> preview.packageId == packageId },
                    )
                }
            } else {
                mutableState.update { it.copy(errors = listOf(PackageErrorItem(packageId, failure)) + it.errors) }
            }
        }
    }

    /** Opens the developer-tools UI preview for a registered package. */
    fun preview(packageId: String) {
        val packageToPreview = manager.registry.find(packageId) ?: return
        mutableState.update {
            it.copy(
                preview = PackageUiPreview(
                    packageId = packageToPreview.packageId,
                    displayName = packageToPreview.displayName,
                    version = packageToPreview.version,
                    definition = packageToPreview.definition,
                ),
            )
        }
    }

    fun dismissPreview() = mutableState.update { it.copy(preview = null) }

    fun select(packageId: String?) {
        mutableState.update { it.copy(selectedPackageId = if (it.selectedPackageId == packageId) null else packageId) }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun clearErrors() = mutableState.update { it.copy(errors = emptyList()) }

    private fun commitInstall(file: File, sourceName: String, replace: Boolean): InstallOutcome =
        runCatching { manager.install(file.readBytes(), sourceName, replace) }
            .fold(
                onSuccess = { result ->
                    when (result) {
                        is PackageInstallResult.Installed -> InstallOutcome.Installed(
                            tempFile = file,
                            packageId = result.packageInstalled.packageId,
                        )
                        is PackageInstallResult.Conflict -> InstallOutcome.NeedsReplace(
                            tempFile = file,
                            sourceName = sourceName,
                            prompt = ReplacePrompt(
                                packageId = result.existing.packageId,
                                displayName = result.candidate.displayName,
                                existingVersion = result.existing.version,
                                candidateVersion = result.candidate.version,
                            ),
                        )
                    }
                },
                onFailure = { failure ->
                    InstallOutcome.Failed(tempFile = file, error = PackageErrorItem(sourceName, failure.asPackageError(sourceName)))
                },
            )

    private fun consume(outcome: InstallOutcome) {
        when (outcome) {
            is InstallOutcome.Installed -> {
                outcome.tempFile.delete()
                mutableState.update { it.copy(installing = false, message = "Installed ${outcome.packageId}") }
            }
            is InstallOutcome.NeedsReplace -> {
                pendingInstallFile = outcome.tempFile
                pendingSourceName = outcome.sourceName
                mutableState.update { it.copy(installing = false, replacePrompt = outcome.prompt) }
            }
            is InstallOutcome.Failed -> {
                outcome.tempFile?.delete()
                mutableState.update {
                    it.copy(installing = false, errors = listOf(outcome.error) + it.errors)
                }
            }
        }
    }

    private fun clearPendingInstall() {
        pendingInstallFile?.delete()
        pendingInstallFile = null
        pendingSourceName = null
    }

    private fun copyToCache(uri: Uri): File {
        val context = getApplication<Application>()
        return try {
            val directory = File(context.cacheDir, INSTALL_CACHE_DIRECTORY).apply { mkdirs() }
            val file = File(directory, "install-${System.nanoTime()}.dcpkg")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw PackageException(PackageError.StorageFailure("cannot open the selected file"))
            input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
            if (file.length() > MAX_ARCHIVE_BYTES) {
                val length = file.length()
                file.delete()
                throw PackageException(
                    PackageError.SizeLimitExceeded(
                        detail = "archive is $length bytes, limit is $MAX_ARCHIVE_BYTES bytes",
                        path = uri.lastPathSegment,
                    ),
                )
            }
            file
        } catch (error: PackageException) {
            throw error
        } catch (error: Exception) {
            throw PackageException(PackageError.StorageFailure(error.message ?: "cannot read the selected file"))
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun toListItem(packageItem: DevicePackage) = PackageListItem(
        packageId = packageItem.packageId,
        displayName = packageItem.displayName,
        version = packageItem.version,
        sourceName = packageItem.sourceName,
        isBuiltIn = packageItem.isBuiltIn,
        capabilities = packageItem.capabilities,
        matcherCount = packageItem.matcherCount,
        assetCount = packageItem.assets.size,
    )

    private fun toErrorItem(failure: PackageLoadFailure) = PackageErrorItem(failure.sourceName, failure.error)

    private sealed interface InstallOutcome {
        val tempFile: File?

        data class Installed(override val tempFile: File, val packageId: String) : InstallOutcome
        data class NeedsReplace(
            override val tempFile: File,
            val sourceName: String,
            val prompt: ReplacePrompt,
        ) : InstallOutcome
        data class Failed(override val tempFile: File?, val error: PackageErrorItem) : InstallOutcome
    }

    companion object {
        private const val INSTALL_CACHE_DIRECTORY = "package-install"
        private val MAX_ARCHIVE_BYTES = PackageLimits().maxArchiveBytes
    }
}

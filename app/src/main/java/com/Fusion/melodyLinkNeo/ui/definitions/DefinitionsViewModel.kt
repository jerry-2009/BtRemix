package com.fusion.melodyLinkNeo.ui.definitions

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fusion.melodyLinkNeo.BtRemixApplication
import com.fusion.melodyLinkNeo.definition.packages.DevicePackage
import com.fusion.melodyLinkNeo.definition.packages.InstalledPackage
import com.fusion.melodyLinkNeo.definition.packages.PackageError
import com.fusion.melodyLinkNeo.definition.packages.PackageException
import com.fusion.melodyLinkNeo.definition.packages.PackageInstallResult
import com.fusion.melodyLinkNeo.definition.packages.PackageLimits
import com.fusion.melodyLinkNeo.definition.packages.asPackageError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Install sheet stages (DEVICE_CENTER_UI_PLAN §5.7). */
enum class InstallStage { IDLE, READING, PREVIEW, CONFLICT, INSTALLING, DONE, ERROR }

data class PackagePreviewUi(
    val packageId: String,
    val displayName: String,
    val version: String,
    val author: String?,
    val description: String?,
    val capabilities: List<String>,
    val matcherCount: Int,
    val assetCount: Int,
    val sizeBytes: Long,
    val existingVersion: String? = null,
)

data class DefinitionsUiState(
    val packages: List<InstalledPackage> = emptyList(),
    val loading: Boolean = true,
    val stage: InstallStage = InstallStage.IDLE,
    val preview: PackagePreviewUi? = null,
    val message: String? = null,
    val error: String? = null,
)

/**
 * Definitions page state (DEVICE_CENTER_UI_PLAN §5.4/§5.7).
 *
 * All package semantics go through [com.fusion.melodyLinkNeo.definition.packages.DevicePackageRepository],
 * so enable/disable and install metadata stay consistent with what `DeviceRegistry` and the Melody
 * projection see.
 */
class DefinitionsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BtRemixApplication
    private val repository = app.packageRepository
    private val bootstrap = app.packageBootstrap

    private val local = MutableStateFlow(DefinitionsUiState())
    private var pendingFile: File? = null
    private var pendingSourceName: String? = null

    val state: StateFlow<DefinitionsUiState> = combine(repository.packages, bootstrap.ready, local) { packages, ready, ui ->
        ui.copy(packages = packages, loading = !ready)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefinitionsUiState())

    fun setEnabled(packageId: String, enabled: Boolean) {
        repository.setEnabled(packageId, enabled)
    }

    fun uninstall(packageId: String) {
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { repository.uninstall(packageId) }.exceptionOrNull()?.asPackageError(packageId)
            }
            if (failure == null) {
                local.update { it.copy(message = "已卸载 $packageId") }
            } else {
                local.update { it.copy(error = failure.display()) }
            }
        }
    }

    /** Reads and validates the picked file, then shows the preview (§5.7 Idle→Reading→Preview). */
    fun pick(uri: Uri) {
        clearPending()
        viewModelScope.launch {
            local.update { it.copy(stage = InstallStage.READING, error = null, preview = null, message = null) }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val file = copyToCache(uri)
                    val sourceName = displayName(uri) ?: file.name
                    val loaded = app.packages.load(file.readBytes(), sourceName)
                    pendingFile = file
                    pendingSourceName = sourceName
                    loaded to file.length()
                }
            }
            outcome.fold(
                onSuccess = { (loaded, size) ->
                    local.update { it.copy(stage = InstallStage.PREVIEW, preview = loaded.toPreview(size)) }
                },
                onFailure = { failure ->
                    local.update { it.copy(stage = InstallStage.ERROR, error = failure.asPackageError(uri.lastPathSegment).display()) }
                },
            )
        }
    }

    /** Commits the previewed install; a conflicting id moves to the conflict stage instead. */
    fun confirmInstall() {
        install(replace = false)
    }

    fun confirmReplace() {
        install(replace = true)
    }

    private fun install(replace: Boolean) {
        val file = pendingFile ?: return
        val sourceName = pendingSourceName ?: file.name
        viewModelScope.launch {
            local.update { it.copy(stage = InstallStage.INSTALLING, error = null) }
            val outcome = withContext(Dispatchers.IO) {
                runCatching { repository.install(file.readBytes(), sourceName, replace) }
            }
            outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is PackageInstallResult.Installed -> {
                            clearPending()
                            app.activity.record(
                                kind = com.fusion.melodyLinkNeo.core.activity.ActivityKind.PACKAGE,
                                title = "加载设备包",
                                detail = "${result.packageInstalled.displayName} v${result.packageInstalled.version}",
                                packageId = result.packageInstalled.packageId,
                            )
                            local.update {
                                it.copy(stage = InstallStage.DONE, preview = null, message = "安装成功：${result.packageInstalled.displayName}")
                            }
                        }
                        is PackageInstallResult.Conflict -> local.update {
                            it.copy(
                                stage = InstallStage.CONFLICT,
                                preview = result.candidate.toPreview(file.length()).copy(existingVersion = result.existing.version),
                            )
                        }
                    }
                },
                onFailure = { failure ->
                    local.update { it.copy(stage = InstallStage.ERROR, error = failure.asPackageError(sourceName).display()) }
                },
            )
        }
    }

    fun cancelInstall() {
        clearPending()
        local.update { it.copy(stage = InstallStage.IDLE, preview = null, error = null) }
    }

    fun dismissMessage() = local.update { it.copy(message = null, stage = InstallStage.IDLE) }

    fun dismissError() = local.update { it.copy(error = null, stage = InstallStage.IDLE) }

    /** Update sources are not implemented this milestone; make that explicit instead of a dead entry. */
    fun checkUpdate() {
        local.update { it.copy(message = "更新源未配置") }
    }

    fun reload() {
        viewModelScope.launch { withContext(Dispatchers.IO) { bootstrap.load() } }
    }

    private fun clearPending() {
        pendingFile?.delete()
        pendingFile = null
        pendingSourceName = null
    }

    private fun DevicePackage.toPreview(sizeBytes: Long) = PackagePreviewUi(
        packageId = packageId,
        displayName = displayName,
        version = version,
        author = definition.manifest.author,
        description = definition.manifest.description,
        capabilities = capabilities,
        matcherCount = matcherCount,
        assetCount = assets.size,
        sizeBytes = sizeBytes,
    )

    private fun copyToCache(uri: Uri): File {
        val context = getApplication<Application>()
        val directory = File(context.cacheDir, "package-install").apply { mkdirs() }
        val file = File(directory, "install-${System.nanoTime()}.dcpkg")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw PackageException(PackageError.StorageFailure("无法读取所选文件"))
        input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
        if (file.length() > PackageLimits().maxArchiveBytes) {
            file.delete()
            throw PackageException(PackageError.SizeLimitExceeded("文件超过 10MB 上限", uri.lastPathSegment))
        }
        return file
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
}

private fun PackageError.display(): String = when (this) {
    is PackageError.PackageConflict -> "${detail}"
    is PackageError.DefinitionInvalid -> "定义无效：${detail}"
    is PackageError.UnsupportedFormat -> "不支持的包格式：${detail}"
    is PackageError.SizeLimitExceeded -> "文件超出限制：${detail}"
    is PackageError.StorageFailure -> "存储失败：${detail}"
    is PackageError.InvalidZip -> "不是有效的 .dcpkg：${detail}"
    is PackageError.MissingEntry -> detail
    is PackageError.UnsafePath -> "不安全的包路径：${detail}"
    is PackageError.MetadataMismatch -> "包元数据不一致：${detail}"
}

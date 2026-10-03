package com.Fusion.Btremix.ui.packages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.Fusion.Btremix.definition.api.initialState
import com.Fusion.Btremix.ui.renderer.DefinitionDevicePage

private val PACKAGE_MIME_TYPES = arrayOf("application/zip", "application/octet-stream", "*/*")

/**
 * Developer tools screen for listing, installing and deleting `.dcpkg` device packages.
 *
 * This screen never touches BLE or the Definition runtime directly: it renders
 * [PackageToolsUiState] and forwards intents to the ViewModel.
 */
@Composable
fun DefinitionPackagesScreen(
    state: PackageToolsUiState,
    onPackagePicked: (Uri) -> Unit,
    onReload: () -> Unit,
    onDelete: (String) -> Unit,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
    onDismissPreview: () -> Unit,
    onConfirmReplace: () -> Unit,
    onCancelReplace: () -> Unit,
    onDismissMessage: () -> Unit,
    onClearErrors: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPackagePicked(uri)
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Definition packages", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${state.packages.size} registered · ${state.packages.count { !it.isBuiltIn }} installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onReload, enabled = !state.loading && !state.installing) { Text("Reload") }
        }

        Button(
            onClick = { picker.launch(PACKAGE_MIME_TYPES) },
            enabled = !state.installing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.installing) "Installing…" else "Install package (.dcpkg)")
        }
        Spacer(Modifier.height(10.dp))

        state.message?.let { message ->
            MessageBanner(message, onDismissMessage)
            Spacer(Modifier.height(8.dp))
        }

        if (state.errors.isNotEmpty()) {
            PackageErrors(state.errors, onClearErrors)
            Spacer(Modifier.height(8.dp))
        }

        if (state.loading) {
            Text("Loading packages…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        }

        if (!state.loading && state.packages.isEmpty()) {
            Text("No packages registered", Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.packages, key = { it.packageId }) { item ->
                    PackageRow(
                        item = item,
                        selected = item.packageId == state.selectedPackageId,
                        onSelect = { onSelect(item.packageId) },
                        onPreview = { onPreview(item.packageId) },
                        onDelete = { onDelete(item.packageId) },
                    )
                }
            }
        }
    }

    state.replacePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onCancelReplace,
            title = { Text("Replace package?") },
            text = {
                Text(
                    "'${prompt.packageId}' is already installed (v${prompt.existingVersion}). " +
                        "Install ${prompt.displayName} v${prompt.candidateVersion} instead?",
                )
            },
            confirmButton = { TextButton(onClick = onConfirmReplace) { Text("Replace") } },
            dismissButton = { TextButton(onClick = onCancelReplace) { Text("Cancel") } },
        )
    }

    state.preview?.let { preview -> PackagePreviewDialog(preview, onDismissPreview) }
}

@Composable
private fun PackagePreviewDialog(preview: PackageUiPreview, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("UI preview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${preview.packageId} v${preview.version} · rendered from the ui section",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text(
                    "Default state values are shown. Actions are not sent to a device in preview mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val previewState = remember(preview.definition) { preview.definition.initialState() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    DefinitionDevicePage(
                        definition = preview.definition,
                        state = previewState,
                        onAction = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun PackageErrors(errors: List<PackageErrorItem>, onClearErrors: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Package errors", Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Medium)
                TextButton(onClick = onClearErrors) { Text("Clear") }
            }
            errors.forEach { item ->
                Text(
                    buildPackageError(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun PackageRow(
    item: PackageListItem,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.displayName, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text("v${item.version}", style = MaterialTheme.typography.bodySmall)
            }
            Text(item.packageId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceBadge(if (item.isBuiltIn) "Built-in" else "Installed")
                Text("${item.matcherCount} matchers", style = MaterialTheme.typography.labelSmall)
                Text("${item.assetCount} assets", style = MaterialTheme.typography.labelSmall)
            }
            if (item.capabilities.isNotEmpty()) {
                Text(
                    "Capabilities: ${item.capabilities.joinToString()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (selected) {
                Divider(Modifier.padding(vertical = 6.dp))
                Text("Source: ${item.sourceName}", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onPreview) { Text("Preview UI") }
                if (!item.isBuiltIn) {
                    TextButton(onClick = onDelete) { Text("Delete package") }
                } else {
                    Text("Built-in packages cannot be deleted", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun buildPackageError(item: PackageErrorItem): String {
    val source = item.sourceName?.let { "$it · " } ?: ""
    val path = item.error.path?.let { " [$it]" } ?: ""
    return "$source${item.error.code.name}$path ${item.error.detail}"
}

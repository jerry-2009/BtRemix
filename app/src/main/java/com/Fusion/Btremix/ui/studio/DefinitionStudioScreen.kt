package com.Fusion.Btremix.ui.studio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.ui.renderer.DefinitionDevicePage

/**
 * Third-party developer studio: edit, validate, simulate and export a Definition without writing
 * Kotlin or touching source control.
 */
@Composable
fun DefinitionStudioScreen(
    state: StudioUiState,
    onEditorChange: (String) -> Unit,
    onValidate: () -> Unit,
    onSaveDraft: (String) -> Unit,
    onLoadDraft: (String) -> Unit,
    onDeleteDraft: (String) -> Unit,
    onToggleSimulation: () -> Unit,
    onAction: (DeviceAction) -> Unit,
    onSelectTarget: (Int) -> Unit,
    onNotificationHex: (String) -> Unit,
    onInject: () -> Unit,
    onExport: (Uri) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftName by rememberSaveable { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) onExport(uri)
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Definition Studio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Author a .dcpkg definition, validate it, simulate it and export it — no build step.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.message?.let { message ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                }
            }
        }
        if (state.errors.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Validation errors", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    state.errors.forEach { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        OutlinedTextField(
            value = state.editor,
            onValueChange = onEditorChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("definition.json") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            // Keep the primary actions reachable without scrolling on a phone-sized viewport.
            minLines = 8,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onValidate) { Text("Validate") }
            OutlinedButton(onClick = onToggleSimulation, enabled = !state.busy) {
                Text(if (state.simulator == null) "Simulate" else "Stop simulation")
            }
            OutlinedButton(
                enabled = state.definitionId != null,
                onClick = { exportLauncher.launch("${state.definitionId}-${state.version}.dcpkg") },
            ) { Text("Export .dcpkg") }
        }
        state.definitionId?.let { id ->
            Text("Parsed: $id v${state.version} · schema ${state.schemaVersion}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Divider()
        Text("Drafts", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it },
                modifier = Modifier.weight(1f),
                label = { Text("Draft name") },
                singleLine = true,
            )
            Button(onClick = { onSaveDraft(draftName) }, enabled = draftName.isNotBlank()) { Text("Save") }
        }
        if (state.drafts.isEmpty()) {
            Text("No drafts yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.drafts.forEach { draft ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(draft.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(draft.definitionId, draft.version).joinToString(" · ").ifBlank { "unreadable" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onLoadDraft(draft.name) }) { Text("Load") }
                    TextButton(onClick = { onDeleteDraft(draft.name) }) { Text("Delete") }
                }
            }
        }

        state.simulator?.let { simulator ->
            Divider()
            SimulatorSection(simulator, onAction, onSelectTarget, onNotificationHex, onInject)
        }
    }
}

@Composable
private fun SimulatorSection(
    simulator: SimulatorUiState,
    onAction: (DeviceAction) -> Unit,
    onSelectTarget: (Int) -> Unit,
    onNotificationHex: (String) -> Unit,
    onInject: () -> Unit,
) {
    Text("Simulator", style = MaterialTheme.typography.titleSmall)
    Text(
        "${simulator.definition.id} v${simulator.definition.manifest.version} — actions and notifications run against an in-memory device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    simulator.actionError?.let { error ->
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
            Text("$error", Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    DefinitionDevicePage(
        definition = simulator.definition,
        state = simulator.state,
        onAction = onAction,
    )

    Divider()
    Text("Inject notification", style = MaterialTheme.typography.titleSmall)
    if (simulator.notifyTargets.isEmpty()) {
        Text("This definition declares no notification characteristics.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            simulator.notifyTargets.forEachIndexed { index, target ->
                Surface(
                    onClick = { onSelectTarget(index) },
                    color = if (index == simulator.selectedTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        target.characteristicUuid.toString().take(8),
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == simulator.selectedTarget) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = simulator.notificationHex,
                onValueChange = onNotificationHex,
                modifier = Modifier.weight(1f),
                label = { Text("Notification HEX") },
                placeholder = { Text("4D") },
                singleLine = true,
            )
            Button(onClick = onInject) { Text("Inject") }
        }
    }

    Spacer(Modifier.height(4.dp))
    Text("Recorded writes (${simulator.writes.size})", style = MaterialTheme.typography.titleSmall)
    if (simulator.writes.isEmpty()) {
        Text("No writes yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        simulator.writes.takeLast(20).forEach { write ->
            Text(write, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
    simulator.message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
}

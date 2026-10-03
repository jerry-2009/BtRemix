package com.Fusion.Btremix.ui.studio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.json.DefinitionJsonException
import com.Fusion.Btremix.definition.json.JsonParseException
import com.Fusion.Btremix.definition.packages.DevicePackageWriter
import com.Fusion.Btremix.definition.simulator.DeviceSimulator
import com.Fusion.Btremix.definition.simulator.SimulatorNotifyTarget
import com.Fusion.Btremix.definition.validator.DefinitionValidationException
import com.Fusion.Btremix.device.runtime.ActionResult
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.StateEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One saved draft on disk. */
data class DraftSummary(val name: String, val definitionId: String?, val version: String?)

data class SimulatorUiState(
    val definition: LoadedDeviceDefinition,
    val state: Map<String, StateEntry> = emptyMap(),
    val writes: List<String> = emptyList(),
    val actionError: String? = null,
    val notifyTargets: List<SimulatorNotifyTarget> = emptyList(),
    val selectedTarget: Int = 0,
    val notificationHex: String = "",
    val message: String? = null,
)

data class StudioUiState(
    val editor: String = DEFAULT_STUDIO_TEMPLATE,
    val definitionId: String? = null,
    val version: String? = null,
    val schemaVersion: Int? = null,
    val errors: List<String> = emptyList(),
    val message: String? = null,
    val drafts: List<DraftSummary> = emptyList(),
    val simulator: SimulatorUiState? = null,
    val busy: Boolean = false,
)

/**
 * Developer studio: edit a Definition as JSON, validate it with the production validator, run it
 * against an in-memory device simulator and export it as a `.dcpkg`.
 *
 * The editor deliberately edits raw JSON: a structured form would duplicate the schema and lag
 * behind new fields, while the validator already produces precise, path-tagged errors.
 */
class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val draftsDirectory = File(application.filesDir, DRAFTS_DIRECTORY)
    private val mutableState = MutableStateFlow(StudioUiState())
    val state: StateFlow<StudioUiState> = mutableState.asStateFlow()

    private var lastValid: LoadedDeviceDefinition? = null
    private var simulator: DeviceSimulator? = null
    private var simulatorJobs = mutableListOf<Job>()

    init {
        refreshDrafts()
    }

    fun updateEditor(text: String) {
        mutableState.update { it.copy(editor = text, message = null) }
    }

    /** Parses and validates the editor contents; returns true when the document is usable. */
    fun validate(): Boolean {
        val outcome = parse(mutableState.value.editor)
        return when (outcome) {
            is ParseOutcome.Valid -> {
                lastValid = outcome.definition
                mutableState.update {
                    it.copy(
                        errors = emptyList(),
                        definitionId = outcome.definition.id,
                        version = outcome.definition.manifest.version,
                        schemaVersion = outcome.definition.manifest.schemaVersion,
                        message = "Valid definition",
                    )
                }
                true
            }
            is ParseOutcome.Invalid -> {
                lastValid = null
                mutableState.update { it.copy(errors = outcome.messages, message = null) }
                false
            }
        }
    }

    fun saveDraft(name: String) {
        val trimmed = name.trim().ifBlank { return }
        if (!validate()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                draftsDirectory.mkdirs()
                File(draftsDirectory, "${sanitize(trimmed)}.json").writeText(mutableState.value.editor)
            }
            refreshDrafts()
            mutableState.update { it.copy(message = "Saved draft $trimmed") }
        }
    }

    fun loadDraft(name: String) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { File(draftsDirectory, "$name.json").readText() }.getOrNull()
            }
            if (text != null) {
                mutableState.update { it.copy(editor = text, message = "Loaded draft $name") }
                validate()
            }
        }
    }

    fun deleteDraft(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { File(draftsDirectory, "$name.json").delete() } }
            refreshDrafts()
        }
    }

    fun refreshDrafts() {
        viewModelScope.launch {
            val drafts = withContext(Dispatchers.IO) {
                draftsDirectory.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".json") }
                    .sortedBy { it.name }
                    .map { file ->
                        val definition = runCatching { DefinitionJsonCodec.decode(file.readText()) }.getOrNull()
                        DraftSummary(
                            name = file.name.removeSuffix(".json"),
                            definitionId = definition?.id,
                            version = definition?.manifest?.version,
                        )
                    }
            }
            mutableState.update { it.copy(drafts = drafts) }
        }
    }

    fun startSimulation() {
        if (!validate()) return
        val definition = lastValid ?: return
        stopSimulation()
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            val active = DeviceSimulator(definition)
            val session = runCatching { active.start() }.getOrElse { error ->
                mutableState.update { it.copy(busy = false, errors = listOf(error.message ?: "Simulator failed to start")) }
                return@launch
            }
            simulator = active
            mutableState.update {
                it.copy(
                    busy = false,
                    simulator = SimulatorUiState(
                        definition = definition,
                        state = session.state.entries.value,
                        notifyTargets = active.notifyTargets,
                    ),
                    message = "Simulating ${definition.id}",
                )
            }
            simulatorJobs += viewModelScope.launch {
                session.state.entries.collect { entries ->
                    mutableState.update { current -> current.copy(simulator = current.simulator?.copy(state = entries)) }
                }
            }
        }
    }

    fun stopSimulation() {
        simulatorJobs.forEach(Job::cancel)
        simulatorJobs.clear()
        val active = simulator
        simulator = null
        if (active != null) viewModelScope.launch { runCatching { active.stop() } }
        mutableState.update { it.copy(simulator = null) }
    }

    fun runSimulatorAction(action: DeviceAction) {
        val active = simulator ?: return
        val session = active.session ?: return
        viewModelScope.launch {
            val result = session.execute(action)
            val error = (result as? ActionResult.Failure)?.error?.let { "${it.code}: ${it.message}" }
            mutableState.update { current ->
                current.copy(
                    simulator = current.simulator?.copy(
                        actionError = error,
                        writes = active.writes.map { "${it.characteristicUuid} ${it.data.toHex()}" },
                    ),
                )
            }
        }
    }

    fun selectNotifyTarget(index: Int) {
        mutableState.update { current ->
            current.copy(simulator = current.simulator?.copy(selectedTarget = index))
        }
    }

    fun setNotificationHex(text: String) {
        mutableState.update { current ->
            current.copy(simulator = current.simulator?.copy(notificationHex = text))
        }
    }

    fun injectNotification() {
        val active = simulator ?: return
        val ui = mutableState.value.simulator ?: return
        val target = ui.notifyTargets.getOrNull(ui.selectedTarget) ?: return
        val bytes = parseHex(ui.notificationHex)
        if (bytes == null) {
            mutableState.update { it.copy(simulator = ui.copy(message = "Enter valid HEX bytes")) }
            return
        }
        viewModelScope.launch {
            active.injectNotification(target, bytes)
            mutableState.update { it.copy(simulator = ui.copy(message = "Injected ${bytes.size} bytes")) }
        }
    }

    /** Writes the current definition as a `.dcpkg` to the SAF destination chosen by the user. */
    fun export(uri: Uri) {
        if (!validate()) return
        val definition = lastValid ?: return
        val bytes = DevicePackageWriter.encode(
            packageId = definition.id,
            version = definition.manifest.version,
            definitionJson = mutableState.value.editor,
        )
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("cannot open the selected file")
                }
            }
            mutableState.update {
                it.copy(
                    message = outcome.fold(
                        onSuccess = { "Exported ${definition.id} v${definition.manifest.version}" },
                        onFailure = { error -> "Export failed: ${error.message}" },
                    ),
                )
            }
        }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    private sealed interface ParseOutcome {
        data class Valid(val definition: LoadedDeviceDefinition) : ParseOutcome
        data class Invalid(val messages: List<String>) : ParseOutcome
    }

    private fun parse(text: String): ParseOutcome = try {
        ParseOutcome.Valid(DefinitionJsonCodec.decode(text))
    } catch (error: DefinitionValidationException) {
        ParseOutcome.Invalid(error.errors.map { "${it.path}: ${it.message}" })
    } catch (error: DefinitionJsonException) {
        ParseOutcome.Invalid(listOf("${error.path}: ${error.message}"))
    } catch (error: JsonParseException) {
        ParseOutcome.Invalid(listOf(error.message ?: "Invalid JSON"))
    } catch (error: IllegalArgumentException) {
        ParseOutcome.Invalid(listOf(error.message ?: "Invalid definition"))
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "draft" }

    private fun parseHex(input: String): ByteArray? {
        val tokens = input.trim().split(Regex("[\\s,:-]+")).filter(String::isNotBlank)
        if (tokens.isEmpty() || tokens.any { it.length !in 1..2 || it.toIntOrNull(16) == null }) return null
        return tokens.map { it.toInt(16).toByte() }.toByteArray()
    }

    private companion object {
        const val DRAFTS_DIRECTORY = "definition-drafts"
    }
}

private val DEFAULT_STUDIO_TEMPLATE = """
            {
              "manifest": {
                "id": "vendor.sample",
                "displayName": "Sample Device",
                "version": "1.0.0",
                "schemaVersion": 2,
                "matchers": [{ "type": "namePrefix", "value": "Sample" }]
              },
              "protocol": {
                "packet": { "includesSequence": true },
                "transport": {
                  "service": "0000ffe0-0000-1000-8000-00805f9b34fb",
                  "characteristic": "0000ffe1-0000-1000-8000-00805f9b34fb"
                },
                "messages": {
                  "setMode": {
                    "fields": [
                      { "name": "mode", "type": "uint8" }
                    ]
                  }
                },
                "transactions": {
                  "mode.set": {
                    "requestCommand": 17,
                    "requestMessage": "setMode",
                    "expectedCommand": 145,
                    "responseMessage": "setMode"
                  }
                }
              },
              "states": {
                "enabled": { "type": "boolean", "displayName": "Enabled", "default": false },
                "battery": {
                  "type": "integer",
                  "displayName": "Battery",
                  "unit": "%",
                  "default": 100,
                  "notify": {
                    "service": "0000180f-0000-1000-8000-00805f9b34fb",
                    "characteristic": "00002a19-0000-1000-8000-00805f9b34fb",
                    "decode": { "at": [{ "var": "raw" }, 0] }
                  }
                }
              },
              "actions": {
                "power.set": {
                  "displayName": "Set power",
                  "parameters": [{ "name": "value", "type": "boolean" }],
                  "resultState": "enabled",
                  "transaction": "mode.set",
                  "arguments": { "mode": { "arg": "value" } }
                }
              },
              "ui": {
                "title": "Sample Device",
                "children": [
                  { "type": "progress", "state": "battery" },
                  { "type": "switch", "state": "enabled", "action": "power.set" }
                ]
              }
            }
""".trimIndent()

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xff) }

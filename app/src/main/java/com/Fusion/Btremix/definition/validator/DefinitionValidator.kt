package com.Fusion.Btremix.definition.validator

import com.Fusion.Btremix.definition.api.DeviceMatchRule
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.ProtocolFieldDefinition
import com.Fusion.Btremix.definition.api.ProtocolFieldType
import com.Fusion.Btremix.definition.api.ProtocolFieldType.*
import com.Fusion.Btremix.definition.api.*
import com.Fusion.Btremix.definition.capability.CapabilityRegistry
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.protocol.api.ChecksumRegistry
import com.Fusion.Btremix.protocol.api.ChecksumSpec
import com.Fusion.Btremix.protocol.api.ChecksumCoverage
import com.Fusion.Btremix.protocol.api.FrameSplitterRegistry
import com.Fusion.Btremix.protocol.api.FrameLayoutRules
import com.Fusion.Btremix.protocol.api.FrameLayoutSpec
import com.Fusion.Btremix.protocol.api.FrameSegmentRole
import com.Fusion.Btremix.protocol.api.MAX_COMMAND
import com.Fusion.Btremix.protocol.api.PacketMatcherSpec
import com.Fusion.Btremix.scripting.api.ScriptProgram
import com.Fusion.Btremix.scripting.api.ScriptStep
import java.util.UUID

data class DefinitionValidationError(val path: String, val message: String)

class DefinitionValidationException(
    val errors: List<DefinitionValidationError>,
) : IllegalArgumentException(errors.joinToString("; ") { "${it.path}: ${it.message}" })

object DefinitionValidator {
    fun validate(definition: LoadedDeviceDefinition): List<DefinitionValidationError> {
        val errors = mutableListOf<DefinitionValidationError>()
        val manifest = definition.manifest
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(manifest.id)) {
            errors += DefinitionValidationError("manifest.id", "must contain only letters, digits, '.', '_' or '-'")
        }
        if (manifest.displayName.isBlank()) errors += DefinitionValidationError("manifest.displayName", "must not be blank")
        if (manifest.version.isBlank()) errors += DefinitionValidationError("manifest.version", "must not be blank")
        if (manifest.schemaVersion !in DefinitionSchema.SUPPORTED) {
            errors += DefinitionValidationError(
                "manifest.schemaVersion",
                "unsupported schema version ${manifest.schemaVersion}, supported ${DefinitionSchema.SUPPORTED}",
            )
        }
        CapabilityRegistry.validateRuntimeApi(manifest.runtimeApi)?.let { message ->
            errors += DefinitionValidationError("manifest.runtimeApi", message)
        }
        CapabilityRegistry.validateRequires(manifest.requires, manifest.runtimeApi).forEach { error ->
            errors += DefinitionValidationError("manifest.requires[${error.index}]", error.message)
        }
        if (manifest.matchers.isEmpty()) errors += DefinitionValidationError("manifest.matchers", "must contain at least one rule")
        manifest.matchers.forEachIndexed { index, matcher -> validateMatcher(matcher, "manifest.matchers[$index]", errors) }
        definition.melody?.let { melody -> validateMelody(melody, manifest, definition.states, errors) }

        definition.states.forEach { (key, state) ->
            if (key.isBlank() || state.key != key) errors += DefinitionValidationError("states.$key.key", "must match the state map key")
            if (state.displayName?.isBlank() == true) errors += DefinitionValidationError("states.$key.displayName", "must not be blank")
            validateRange(state.min, state.max, state.step, "states.$key", errors)
            if (state.type != StateDefinitionType.ENUM && state.enumValues.isNotEmpty()) errors += DefinitionValidationError("states.$key.enumValues", "is only valid for enum states")
            if (state.type == StateDefinitionType.ENUM && state.enumValues.isEmpty()) errors += DefinitionValidationError("states.$key.enumValues", "must not be empty")
            state.defaultValue?.let { if (!matchesType(it, state.type)) errors += DefinitionValidationError("states.$key.default", "does not match ${state.type}") }
            state.notify?.let { notify ->
                validateNotify(notify, state, manifest, definition.protocol.transport?.type, "states.$key", errors)
            }
        }
        definition.actions.forEach { (id, action) ->
            if (id.isBlank() || action.id != id) errors += DefinitionValidationError("actions.$id.id", "must match the action map key")
            if (action.displayName.isBlank()) errors += DefinitionValidationError("actions.$id.displayName", "must not be blank")
            val parameterNames = action.parameters.map { it.name }
            if (parameterNames.any(String::isBlank) || parameterNames.size != parameterNames.toSet().size) errors += DefinitionValidationError("actions.$id.parameters", "parameter names must be non-blank and unique")
            action.parameters.forEachIndexed { index, parameter ->
                validateRange(parameter.min, parameter.max, parameter.step, "actions.$id.parameters[$index]", errors)
                if (parameter.type != StateDefinitionType.ENUM && parameter.enumValues.isNotEmpty()) errors += DefinitionValidationError("actions.$id.parameters[$index].enumValues", "is only valid for enum parameters")
                if (parameter.type == StateDefinitionType.ENUM && parameter.enumValues.isEmpty()) errors += DefinitionValidationError("actions.$id.parameters[$index].enumValues", "must not be empty")
            }
            action.resultState?.let { if (it !in definition.states) errors += DefinitionValidationError("actions.$id.resultState", "unknown state '$it'") }
            if (action.transaction != null && action.script != null) {
                errors += DefinitionValidationError(
                    "actions.$id.transaction",
                    "cannot be combined with 'script'; pick one execution path",
                )
            }
            action.transaction?.let { transactionName ->
                if (manifest.schemaVersion < DefinitionSchema.VERSION_DECLARATIVE) {
                    errors += DefinitionValidationError("actions.$id.transaction", "requires manifest.schemaVersion ${DefinitionSchema.VERSION_DECLARATIVE}")
                }
                val transaction = definition.protocol.transactions[transactionName]
                if (transaction == null) {
                    errors += DefinitionValidationError("actions.$id.transaction", "unknown transaction '$transactionName'")
                } else {
                    validateArguments(action, transaction, "actions.$id", definition, errors)
                }
            }
            if (action.arguments.isNotEmpty() && action.transaction == null) {
                errors += DefinitionValidationError("actions.$id.arguments", "is only valid with a 'transaction'")
            }
            if (action.result != null && action.resultState == null) {
                errors += DefinitionValidationError("actions.$id.result", "requires 'resultState'")
            }
            if (action.refresh.isNotEmpty()) {
                if (manifest.schemaVersion < DefinitionSchema.VERSION_STREAM) {
                    errors += DefinitionValidationError("actions.$id.refresh", "requires manifest.schemaVersion ${DefinitionSchema.VERSION_STREAM}")
                }
                if (action.transaction == null) {
                    errors += DefinitionValidationError("actions.$id.refresh", "requires 'transaction'")
                }
                if (definition.protocol.transport?.type != TransportType.RFCOMM) {
                    errors += DefinitionValidationError("actions.$id.refresh", "requires an rfcomm protocol.transport")
                }
                action.refresh.forEachIndexed { index, name ->
                    val target = definition.protocol.transactions[name]
                    when {
                        target == null ->
                            errors += DefinitionValidationError("actions.$id.refresh[$index]", "unknown transaction '$name'")
                        // A refreshed transaction carries no action arguments, so it must be a constant request.
                        target.requestPayload == null ->
                            errors += DefinitionValidationError(
                                "actions.$id.refresh[$index]",
                                "transaction '$name' must use 'requestPayload' to be used as a refresh",
                            )
                    }
                }
            }
            action.script?.let { program ->
                validateScriptProgram(
                    program,
                    "actions.$id.script",
                    definition,
                    errors,
                )
            }
        }
        validateUi(definition.ui.children, "ui.children", definition, errors)

        definition.protocol.transport?.let { transport ->
            if (manifest.schemaVersion < DefinitionSchema.VERSION_DECLARATIVE) {
                errors += DefinitionValidationError("protocol.transport", "requires manifest.schemaVersion ${DefinitionSchema.VERSION_DECLARATIVE}")
            }
            validateUuid(transport.service, "protocol.transport.service", errors)
            when (transport.type) {
                TransportType.GATT -> {
                    if (transport.characteristic == null) {
                        errors += DefinitionValidationError("protocol.transport.characteristic", "is required for a gatt transport")
                    } else {
                        validateUuid(transport.characteristic, "protocol.transport.characteristic", errors)
                    }
                }
                TransportType.RFCOMM -> {
                    if (manifest.schemaVersion < DefinitionSchema.VERSION_STREAM) {
                        errors += DefinitionValidationError(
                            "protocol.transport.type",
                            "requires manifest.schemaVersion ${DefinitionSchema.VERSION_STREAM}",
                        )
                    }
                    if (transport.characteristic != null) {
                        errors += DefinitionValidationError(
                            "protocol.transport.characteristic",
                            "must be omitted for an rfcomm transport; 'service' is the SPP UUID",
                        )
                    }
                }
            }
        }
        if (definition.actions.values.any { it.transaction != null } && definition.protocol.transport == null) {
            errors += DefinitionValidationError("protocol.transport", "is required when an action declares a transaction")
        }
        validateFraming(definition, errors)
        val segmented = definition.protocol.framing?.segmented
        if (segmented != null) {
            definition.states.forEach { (key, state) ->
                state.notify?.matcher?.let { matcher ->
                    validatePacketMatcher(
                        matcher,
                        "states.$key.notify.matcher",
                        segmented.request,
                        segmented.response,
                        errors,
                    )
                }
            }
        }
        definition.protocol.initialize.forEachIndexed { index, name ->
            if (manifest.schemaVersion < DefinitionSchema.VERSION_STREAM) {
                errors += DefinitionValidationError("protocol.initialize[$index]", "requires manifest.schemaVersion ${DefinitionSchema.VERSION_STREAM}")
            }
            if (name !in definition.protocol.transactions) {
                errors += DefinitionValidationError("protocol.initialize[$index]", "unknown transaction '$name'")
            }
        }
        validateInitializationPolicy(definition, errors)
        validateTriggers(definition, errors)
        definition.protocol.messages.forEach { (name, message) ->
            if (name.isBlank()) errors += DefinitionValidationError("protocol.messages", "message name must not be blank")
            val names = message.fields.map { it.name }
            if (names.size != names.toSet().size) errors += DefinitionValidationError("protocol.messages.$name.fields", "field names must be unique")
            message.fields.forEachIndexed { index, field ->
                validateField(field, "protocol.messages.$name.fields[$index]", errors)
                if (field.type == BYTES && field.length == null && index != message.fields.lastIndex) {
                    errors += DefinitionValidationError("protocol.messages.$name.fields[$index]", "variable-length bytes must be the last field")
                }
            }
        }
        definition.protocol.transactions.forEach { (name, transaction) ->
            if (name.isBlank()) errors += DefinitionValidationError("protocol.transactions", "transaction name must not be blank")
            if (transaction.requestCommand !in 0..MAX_COMMAND) {
                errors += DefinitionValidationError("protocol.transactions.$name.requestCommand", "must fit UInt24")
            }
            if (transaction.expectedCommand != null && transaction.expectedCommand !in 0..MAX_COMMAND) {
                errors += DefinitionValidationError("protocol.transactions.$name.expectedCommand", "must fit UInt24")
            }
            val segmented = definition.protocol.framing?.segmented
            if (segmented == null && transaction.requestCommand > 0xff) {
                errors += DefinitionValidationError(
                    "protocol.transactions.$name.requestCommand",
                    "must fit UInt8 for a legacy one-byte command codec",
                )
            }
            if (segmented == null && transaction.expectedCommand != null && transaction.expectedCommand > 0xff) {
                errors += DefinitionValidationError(
                    "protocol.transactions.$name.expectedCommand",
                    "must fit UInt8 for a legacy one-byte command codec",
                )
            }
            if (segmented != null) {
                validateCommandWidth(
                    transaction.requestCommand,
                    segmented.request,
                    "protocol.transactions.$name.requestCommand",
                    errors,
                )
                transaction.expectedCommand?.let { command ->
                    validateCommandWidth(
                        command,
                        segmented.response,
                        "protocol.transactions.$name.expectedCommand",
                        errors,
                    )
                }
                transaction.matcher?.let { matcher ->
                    validatePacketMatcher(
                        matcher,
                        "protocol.transactions.$name.matcher",
                        segmented.request,
                        segmented.response,
                        errors,
                    )
                }
            }
            if (transaction.expectedPayloadTypes.isNotEmpty()) {
                if (manifest.schemaVersion < DefinitionSchema.VERSION_STREAM) {
                    errors += DefinitionValidationError("protocol.transactions.$name.expectedPayloadTypes", "requires manifest.schemaVersion ${DefinitionSchema.VERSION_STREAM}")
                }
                if (transaction.expectedPayloadTypes.any { it !in 0..0xff }) {
                    errors += DefinitionValidationError("protocol.transactions.$name.expectedPayloadTypes", "every payload type must fit UInt8")
                }
            }
            if (transaction.requestPayload != null) {
                if (manifest.schemaVersion < DefinitionSchema.VERSION_STREAM) {
                    errors += DefinitionValidationError("protocol.transactions.$name.requestPayload", "requires manifest.schemaVersion ${DefinitionSchema.VERSION_STREAM}")
                }
                if (transaction.requestMessage != null) {
                    errors += DefinitionValidationError("protocol.transactions.$name.requestPayload", "cannot be combined with 'requestMessage'")
                }
            }
            if (transaction.requestMessage != null && transaction.requestMessage !in definition.protocol.messages) {
                errors += DefinitionValidationError("protocol.transactions.$name.requestMessage", "unknown message '${transaction.requestMessage}'")
            }
            if (transaction.responseMessage != null && transaction.responseMessage !in definition.protocol.messages) {
                errors += DefinitionValidationError("protocol.transactions.$name.responseMessage", "unknown message '${transaction.responseMessage}'")
            }
            if (transaction.retries < 0) errors += DefinitionValidationError("protocol.transactions.$name.retries", "must not be negative")
            if (transaction.timeout != null && !transaction.timeout.isPositive()) errors += DefinitionValidationError("protocol.transactions.$name.timeout", "must be positive")
            if (transaction.matcher != null) {
                if (manifest.schemaVersion < DefinitionSchema.VERSION_SEGMENTED) {
                    errors += DefinitionValidationError(
                        "protocol.transactions.$name.matcher",
                        "requires manifest.schemaVersion ${DefinitionSchema.VERSION_SEGMENTED}",
                    )
                }
                if (transaction.expectedPayloadTypes.isNotEmpty()) {
                    errors += DefinitionValidationError(
                        "protocol.transactions.$name.matcher",
                        "cannot be combined with legacy 'expectedPayloadTypes'",
                    )
                }
                if (transaction.expectedCommand != null &&
                    transaction.matcher.command != null &&
                    transaction.expectedCommand != transaction.matcher.command
                ) {
                    errors += DefinitionValidationError(
                        "protocol.transactions.$name.matcher.command",
                        "conflicts with expectedCommand ${transaction.expectedCommand}",
                    )
                }
            }
        }
        return errors
    }

    private fun validateInitializationPolicy(
        definition: LoadedDeviceDefinition,
        errors: MutableList<DefinitionValidationError>,
    ) {
        definition.protocol.initialization.forEachIndexed { index, step ->
            val path = "protocol.initialization[$index]"
            if (definition.manifest.schemaVersion < DefinitionSchema.VERSION_SEGMENTED) {
                errors += DefinitionValidationError(
                    path,
                    "requires manifest.schemaVersion ${DefinitionSchema.VERSION_SEGMENTED}",
                )
            }
            step.transaction?.let { name ->
                if (name !in definition.protocol.transactions) {
                    errors += DefinitionValidationError("$path.transaction", "unknown transaction '$name'")
                }
            }
            step.action?.let { id ->
                val action = definition.actions[id]
                when {
                    action == null ->
                        errors += DefinitionValidationError("$path.action", "unknown action '$id'")
                    action.script == null ->
                        errors += DefinitionValidationError(
                            "$path.action",
                            "initialization action '$id' must use a JSON program",
                        )
                }
            }
            step.program?.let { program ->
                validateScriptProgram(program, "$path.program", definition, errors)
            }
        }
    }

    private fun validateTriggers(
        definition: LoadedDeviceDefinition,
        errors: MutableList<DefinitionValidationError>,
    ) {
        val triggers = definition.triggers.values.toList()
        triggers.forEachIndexed { index, trigger ->
            val path = "triggers.${trigger.id}"
            if (definition.manifest.schemaVersion < DefinitionSchema.VERSION_SEGMENTED) {
                errors += DefinitionValidationError(
                    path,
                    "requires manifest.schemaVersion ${DefinitionSchema.VERSION_SEGMENTED}",
                )
            }
            if (trigger.id != definition.triggers.keys.elementAtOrNull(index)) {
                errors += DefinitionValidationError("$path.id", "must match the trigger map key")
            }
            validatePacketMatcherBasic(trigger.matcher, "$path.matcher", errors)
            if (trigger.matcher.matchRequestSequence) {
                errors += DefinitionValidationError(
                    "$path.matcher.sequence",
                    "an inbound trigger cannot reference a request sequence",
                )
            }
            trigger.action?.let { action ->
                if (action !in definition.actions) {
                    errors += DefinitionValidationError("$path.action", "unknown action '$action'")
                }
            }
            trigger.program?.let { program ->
                validateScriptProgram(program, "$path.program", definition, errors)
            }
            triggers.take(index).forEach { previous ->
                if (matchersPossiblyOverlap(previous.matcher, trigger.matcher)) {
                    errors += DefinitionValidationError(
                        "$path.matcher",
                        "overlaps trigger '${previous.id}'; one inbound frame may match at most one trigger",
                    )
                }
            }
        }
    }

    private fun validateScriptProgram(
        program: ScriptProgram,
        path: String,
        definition: LoadedDeviceDefinition,
        errors: MutableList<DefinitionValidationError>,
        depth: Int = 0,
    ) {
        if (depth > 8) {
            errors += DefinitionValidationError(path, "program nesting exceeds 8")
            return
        }
        fun checkSteps(steps: List<ScriptStep>, at: String) {
            steps.forEachIndexed { index, step ->
                val stepPath = "$at[$index]"
                when (step) {
                    is ScriptStep.GetState -> {
                        if (step.key !in definition.states) {
                            errors += DefinitionValidationError("$stepPath.key", "unknown state '${step.key}'")
                        }
                    }
                    is ScriptStep.SetState -> {
                        if (step.key !in definition.states) {
                            errors += DefinitionValidationError("$stepPath.key", "unknown state '${step.key}'")
                        }
                    }
                    is ScriptStep.If -> {
                        checkSteps(step.thenSteps, "$stepPath.then")
                        checkSteps(step.elseSteps, "$stepPath.else")
                    }
                    is ScriptStep.Repeat -> {
                        if (step.count !in 0..32) {
                            errors += DefinitionValidationError("$stepPath.count", "must be 0..32")
                        }
                        checkSteps(step.steps, "$stepPath.steps")
                    }
                    is ScriptStep.Read,
                    is ScriptStep.Write,
                    is ScriptStep.Subscribe,
                    -> {
                        if (definition.protocol.transport?.type == TransportType.RFCOMM) {
                            errors += DefinitionValidationError(
                                stepPath,
                                "legacy GATT script operations are not available over an rfcomm transport",
                            )
                        }
                    }
                    is ScriptStep.SessionSend,
                    is ScriptStep.SessionRequest,
                    is ScriptStep.SessionWaitFor,
                    is ScriptStep.SessionOnFrame,
                    -> {
                        if (definition.manifest.schemaVersion < DefinitionSchema.VERSION_SEGMENTED) {
                            errors += DefinitionValidationError(
                                stepPath,
                                "requires manifest.schemaVersion ${DefinitionSchema.VERSION_SEGMENTED}",
                            )
                        }
                        if (definition.protocol.transport == null) {
                            errors += DefinitionValidationError(
                                stepPath,
                                "requires protocol.transport",
                            )
                        }
                        when (step) {
                            is ScriptStep.SessionRequest -> {
                                if (step.endpoint !in definition.protocol.transactions) {
                                    errors += DefinitionValidationError(
                                        "$stepPath.request",
                                        "unknown endpoint '${step.endpoint}'",
                                    )
                                }
                                step.timeout?.let { timeout ->
                                    if (!timeout.isPositive() || timeout.inWholeMilliseconds > 60_000) {
                                        errors += DefinitionValidationError(
                                            "$stepPath.timeoutMs",
                                            "must be in 1..60000",
                                        )
                                    }
                                }
                                step.matcher?.let { matcher ->
                                    validatePacketMatcherBasic(matcher, "$stepPath.matcher", errors)
                                    if (matcher.matchRequestSequence) {
                                        errors += DefinitionValidationError(
                                            "$stepPath.matcher.sequence",
                                            "cannot match a request sequence here",
                                        )
                                    }
                                }
                            }
                            is ScriptStep.SessionWaitFor -> {
                                if (!step.timeout.isPositive() || step.timeout.inWholeMilliseconds > 60_000) {
                                    errors += DefinitionValidationError(
                                        "$stepPath.timeoutMs",
                                        "must be in 1..60000",
                                    )
                                }
                                validatePacketMatcherBasic(step.matcher, "$stepPath.matcher", errors)
                                if (step.matcher.matchRequestSequence) {
                                    errors += DefinitionValidationError(
                                        "$stepPath.matcher.sequence",
                                        "waitFor cannot match a request sequence",
                                    )
                                }
                            }
                            is ScriptStep.SessionOnFrame -> {
                                validatePacketMatcherBasic(step.matcher, "$stepPath.matcher", errors)
                                if (step.matcher.matchRequestSequence) {
                                    errors += DefinitionValidationError(
                                        "$stepPath.matcher.sequence",
                                        "onFrame cannot match a request sequence",
                                    )
                                }
                                validateScriptProgram(step.program, "$stepPath.program", definition, errors, depth + 1)
                            }
                            else -> Unit
                        }
                    }
                    is ScriptStep.Emit -> {
                        if (step.name.isBlank()) {
                            errors += DefinitionValidationError("$stepPath.name", "must not be blank")
                        }
                    }
                    is ScriptStep.Assign -> {
                        if (step.name.isBlank()) {
                            errors += DefinitionValidationError("$stepPath.name", "must not be blank")
                        }
                    }
                    is ScriptStep.Return -> Unit
                }
            }
        }
        checkSteps(program.steps, path)
    }

    private fun matchersPossiblyOverlap(left: PacketMatcherSpec, right: PacketMatcherSpec): Boolean {
        if (left.command != null && right.command != null && left.command != right.command) return false
        if (left.sequence != null && right.sequence != null && left.sequence != right.sequence) return false
        if (left.payloadTypes.isNotEmpty() && right.payloadTypes.isNotEmpty() &&
            left.payloadTypes.intersect(right.payloadTypes).isEmpty()
        ) {
            return false
        }
        val leftPrefix = left.payloadPrefix
        val rightPrefix = right.payloadPrefix
        val shared = minOf(leftPrefix.size, rightPrefix.size)
        return leftPrefix.take(shared) == rightPrefix.take(shared)
    }

    private fun validateMelody(
        melody: MelodySectionDefinition,
        manifest: DefinitionManifest,
        states: Map<String, StateDefinition>,
        errors: MutableList<DefinitionValidationError>,
    ) {
        if (manifest.schemaVersion < DefinitionSchema.VERSION_MELODY) {
            errors += DefinitionValidationError(
                "melody",
                "requires manifest.schemaVersion ${DefinitionSchema.VERSION_MELODY}",
            )
        }
        val support = melody.support
        if (support.name.isBlank()) errors += DefinitionValidationError("melody.support.name", "must not be blank")
        if (support.brand?.isBlank() == true) errors += DefinitionValidationError("melody.support.brand", "must not be blank")
        if (support.mode != MelodySupportDefinition.MODE_BRIDGE) {
            errors += DefinitionValidationError(
                "melody.support.mode",
                "only '${MelodySupportDefinition.MODE_BRIDGE}' is supported, not '${support.mode}'",
            )
        }
        support.productId?.let { raw ->
            val normalized = MelodyProductId.normalizeOrNull(raw)
            when {
                normalized == null -> errors += DefinitionValidationError(
                    "melody.support.productId",
                    "must be a decimal integer or a 0x-prefixed hexadecimal string",
                )
                normalized != raw -> errors += DefinitionValidationError(
                    "melody.support.productId",
                    "must use the normalized decimal form '$normalized'",
                )
            }
        }
        if (support.productType < 0) errors += DefinitionValidationError("melody.support.productType", "must not be negative")
        support.uuid?.let { value ->
            runCatching { UUID.fromString(value) }
                .onFailure { errors += DefinitionValidationError("melody.support.uuid", "must be a UUID") }
        }
        if (support.templateWhitelist?.isBlank() == true) {
            errors += DefinitionValidationError("melody.support.templateWhitelist", "must not be blank")
        }
        // M5.4 (D-21/D-26): an optional host version range such as ">=17.6.3 <18". Absent means no
        // restriction; a declared range must parse, otherwise the runtime would silently ignore it.
        support.hostVersions?.let { spec ->
            if (MelodyHostVersions.parse(spec) == null) {
                errors += DefinitionValidationError(
                    MelodyHostVersions.FIELD,
                    "must be whitespace-separated version predicates such as '>=17.6.3 <18'",
                )
            }
        }
        // All three lists name official panel keys, so they share the blank / namespace guard
        // (HANDOFF_MELODY_M4_PLAN.md §3 M4.0 A-2).
        validateMelodyKeys(melody.panel.hideSections, "melody.panel.hideSections", errors)
        validateMelodyKeys(melody.panel.hideKeys, "melody.panel.hideKeys", errors)
        validateMelodyKeys(melody.panel.greyKeys, "melody.panel.greyKeys", errors)
        validateMelodyAnc(melody.anc, states, errors)
    }

    /**
     * The `melody.anc` table (M4.3b D-11/D-12). An empty `modes` list is legal (the module derives
     * the table from the ANC enum state), but whatever is declared must be usable: a host render
     * version M4 knows, a positive `modeType`, a non-negative `protocolIndex`, a non-blank state and
     * no two rows claiming the same host `modeType`. [MelodyAncDefinition.strength] (D-15) becomes the
     * injected `childrenMode` list, so it must be non-empty, use host-renderable child `modeType`s,
     * have unique `protocolIndex`es that do not collide with the parent table, and be ordered by
     * increasing Definition `level`.
     */
    private fun validateMelodyAnc(
        anc: MelodyAncDefinition,
        states: Map<String, StateDefinition>,
        errors: MutableList<DefinitionValidationError>,
    ) {
        if (anc.uiVersion !in MelodyAncDefinition.UI_VERSIONS) {
            errors += DefinitionValidationError(
                "melody.anc.uiVersion",
                "must be one of ${MelodyAncDefinition.UI_VERSIONS.first}..${MelodyAncDefinition.UI_VERSIONS.last}",
            )
        }
        val seen = HashSet<Int>()
        anc.modes.forEachIndexed { index, mode ->
            val path = "melody.anc.modes[$index]"
            if (mode.modeType <= 0) errors += DefinitionValidationError("$path.modeType", "must be positive")
            if (mode.protocolIndex < 0) errors += DefinitionValidationError("$path.protocolIndex", "must not be negative")
            if (mode.state.isBlank()) errors += DefinitionValidationError("$path.state", "must not be blank")
            if (mode.label?.isBlank() == true) errors += DefinitionValidationError("$path.label", "must not be blank")
            if (!seen.add(mode.modeType)) {
                errors += DefinitionValidationError("$path.modeType", "duplicates an earlier modeType ${mode.modeType}")
            }
        }
        anc.strength?.let { strength -> validateMelodyAncStrength(strength, anc.modes, states, errors) }
    }

    private fun validateMelodyAncStrength(
        strength: MelodyAncStrengthDefinition,
        modes: List<MelodyAncMode>,
        states: Map<String, StateDefinition>,
        errors: MutableList<DefinitionValidationError>,
    ) {
        val path = "melody.anc.strength"
        val parentProtocolIndexes = modes.map { it.protocolIndex }.toSet()
        if (strength.state.isBlank()) errors += DefinitionValidationError("$path.state", "must not be blank")
        if (strength.action.isBlank()) errors += DefinitionValidationError("$path.action", "must not be blank")
        if (strength.levels.isEmpty()) {
            errors += DefinitionValidationError("$path.levels", "must contain at least one position")
        }
        strength.levels.forEachIndexed { index, level ->
            val levelPath = "$path.levels[$index]"
            if (level.modeType !in MelodyAncStrengthLevel.HOST_MODE_TYPES) {
                errors += DefinitionValidationError(
                    "$levelPath.modeType",
                    "must be one of ${MelodyAncStrengthLevel.HOST_MODE_TYPES.sorted()} (Low/High/Auto/Moderate)",
                )
            }
            if (level.protocolIndex < 0) {
                errors += DefinitionValidationError("$levelPath.protocolIndex", "must not be negative")
            }
            if (index > 0 && level.level <= strength.levels[index - 1].level) {
                errors += DefinitionValidationError("$levelPath.level", "must be larger than the previous level")
            }
        }
        // The host writes/reads the child `protocolIndex`, so the parent and child tables share one
        // namespace; a collision would highlight or write the wrong row.
        val seenChildModeTypes = HashSet<Int>()
        val seenProtocolIndexes = HashSet<Int>()
        strength.levels.forEachIndexed { index, level ->
            val levelPath = "$path.levels[$index]"
            if (!seenChildModeTypes.add(level.modeType)) {
                errors += DefinitionValidationError("$levelPath.modeType", "duplicates an earlier position")
            }
            if (!seenProtocolIndexes.add(level.protocolIndex)) {
                errors += DefinitionValidationError("$levelPath.protocolIndex", "duplicates an earlier position")
            }
            if (parentProtocolIndexes.contains(level.protocolIndex)) {
                errors += DefinitionValidationError(
                    "$levelPath.protocolIndex",
                    "collides with a top-level mode protocolIndex",
                )
            }
        }
        // A level outside the referenced state's own range could never be matched by a live value, so
        // the projection would be dead on the device without any visible error.
        val state = states[strength.state]
        state?.min?.let { min ->
            if (strength.levels.any { it.level < min }) {
                errors += DefinitionValidationError("$path.levels", "must not be smaller than ${strength.state}.min ($min)")
            }
        }
        state?.max?.let { max ->
            if (strength.levels.any { it.level > max }) {
                errors += DefinitionValidationError("$path.levels", "must not be larger than ${strength.state}.max ($max)")
            }
        }
    }

    private fun validateMelodyKeys(
        keys: List<String>,
        path: String,
        errors: MutableList<DefinitionValidationError>,
    ) {
        keys.forEachIndexed { index, key ->
            when {
                key.isBlank() -> errors += DefinitionValidationError("$path[$index]", "must not be blank")
                key.startsWith(MelodyPanelDefinition.CUSTOM_KEY_PREFIX) -> errors += DefinitionValidationError(
                    "$path[$index]",
                    "must not target the '${MelodyPanelDefinition.CUSTOM_KEY_PREFIX}' namespace used by BtRemix rows",
                )
            }
        }
    }

    private fun validateArguments(
        action: ActionDefinition,
        transaction: TransactionDefinition,
        path: String,
        definition: LoadedDeviceDefinition,
        errors: MutableList<DefinitionValidationError>,
    ) {
        val message = transaction.requestMessage?.let { definition.protocol.messages[it] } ?: return
        val fields = message.fields.map { it.name }.toSet()
        action.arguments.keys.forEach { field ->
            if (field !in fields) {
                errors += DefinitionValidationError("$path.arguments.$field", "is not a field of message '${transaction.requestMessage}'")
            }
        }
        fields.forEach { field ->
            if (field !in action.arguments) {
                errors += DefinitionValidationError("$path.arguments", "missing value for message field '$field'")
            }
        }
    }

    private fun validateUuid(value: String?, path: String, errors: MutableList<DefinitionValidationError>) {
        if (value == null) {
            errors += DefinitionValidationError(path, "is required")
            return
        }
        runCatching { UUID.fromString(value) }
            .onFailure { errors += DefinitionValidationError(path, "must be a UUID") }
    }

    private fun validateNotify(
        notify: NotifyDefinition,
        state: StateDefinition,
        manifest: DefinitionManifest,
        transportType: TransportType?,
        path: String,
        errors: MutableList<DefinitionValidationError>,
    ) {
        val stream = transportType == TransportType.RFCOMM
        if (manifest.schemaVersion < DefinitionSchema.VERSION_DECLARATIVE) {
            errors += DefinitionValidationError("$path.notify", "requires manifest.schemaVersion ${DefinitionSchema.VERSION_DECLARATIVE}")
        }
        notify.matcher?.let { matcher ->
            if (manifest.schemaVersion < DefinitionSchema.VERSION_SEGMENTED) {
                errors += DefinitionValidationError(
                    "$path.notify.matcher",
                    "requires manifest.schemaVersion ${DefinitionSchema.VERSION_SEGMENTED}",
                )
            }
            if (!stream) {
                errors += DefinitionValidationError("$path.notify.matcher", "requires an rfcomm protocol.transport")
            }
            if (notify.payloadTypes.isNotEmpty()) {
                errors += DefinitionValidationError(
                    "$path.notify.matcher",
                    "cannot be combined with legacy 'payloadTypes'",
                )
            }
            if (notify.service != null || notify.characteristic != null) {
                errors += DefinitionValidationError(
                    "$path.notify.matcher",
                    "cannot be combined with 'service'/'characteristic'",
                )
            }
            validatePacketMatcherBasic(matcher, "$path.notify.matcher", errors)
        }
        if (notify.payloadTypes.isNotEmpty()) {
            if (manifest.schemaVersion < DefinitionSchema.VERSION_STREAM) {
                errors += DefinitionValidationError("$path.notify.payloadTypes", "requires manifest.schemaVersion ${DefinitionSchema.VERSION_STREAM}")
            }
            if (!stream) {
                errors += DefinitionValidationError("$path.notify.payloadTypes", "requires an rfcomm protocol.transport")
            }
            if (notify.payloadTypes.any { it !in 0..0xff }) {
                errors += DefinitionValidationError("$path.notify.payloadTypes", "every payload type must fit UInt8")
            }
            if (notify.service != null || notify.characteristic != null) {
                errors += DefinitionValidationError(
                    "$path.notify.payloadTypes",
                    "cannot be combined with 'service'/'characteristic'; a byte stream has no characteristics",
                )
            }
        } else {
            if (stream && notify.matcher == null) {
                errors += DefinitionValidationError("$path.notify", "an rfcomm transport requires 'payloadType'")
            }
            if (notify.matcher == null) {
                validateUuid(notify.service, "$path.notify.service", errors)
                validateUuid(notify.characteristic, "$path.notify.characteristic", errors)
            }
            if (notify.condition != null && notify.matcher == null) {
                errors += DefinitionValidationError("$path.notify.if", "requires 'payloadType'")
            }
        }
        if (notify.decode == null && state.type != StateDefinitionType.BYTES) {
            errors += DefinitionValidationError("$path.notify.decode", "is required unless the state type is bytes")
        }
    }

    private fun validateFraming(
        definition: LoadedDeviceDefinition,
        errors: MutableList<DefinitionValidationError>,
    ) {
        val framing = definition.protocol.framing
        val transport = definition.protocol.transport
        if (framing == null) {
            if (transport?.type == TransportType.RFCOMM) {
                errors += DefinitionValidationError("protocol.framing", "is required for an rfcomm transport")
            }
            return
        }
        if (definition.manifest.schemaVersion < DefinitionSchema.VERSION_STREAM) {
            errors += DefinitionValidationError("protocol.framing", "requires manifest.schemaVersion ${DefinitionSchema.VERSION_STREAM}")
        }
        if (framing.codec !in com.Fusion.Btremix.protocol.api.StreamCodecRegistry.names) {
            errors += DefinitionValidationError(
                "protocol.framing.codec",
                "unknown codec '${framing.codec}', supported ${com.Fusion.Btremix.protocol.api.StreamCodecRegistry.names.sorted()}",
            )
        }
        validateChecksum(framing, errors)
        if (framing.splitter.normalized().function !in FrameSplitterRegistry.names) {
            errors += DefinitionValidationError(
                "protocol.framing.splitter.function",
                "unknown splitter '${framing.splitter.function}', supported ${FrameSplitterRegistry.names.sorted()}",
            )
        }
        if (framing.splitter.maxFrameBytes <= 0) {
            errors += DefinitionValidationError(
                "protocol.framing.splitter.maxFrameBytes",
                "must be positive",
            )
        }
        val segmented = framing.segmented
        if (segmented != null) {
            if (definition.manifest.schemaVersion < DefinitionSchema.VERSION_SEGMENTED) {
                errors += DefinitionValidationError(
                    "protocol.framing.request",
                    "segmented layouts require manifest.schemaVersion ${DefinitionSchema.VERSION_SEGMENTED}",
                )
            }
            if (framing.codec != "segmented") {
                errors += DefinitionValidationError(
                    "protocol.framing.codec",
                    "must be 'segmented' when request/response layouts are declared",
                )
            }
            if (framing.splitter.normalized().function != FrameSplitterRegistry.LENGTH_PREFIXED) {
                errors += DefinitionValidationError(
                    "protocol.framing.splitter.function",
                    "must be '${FrameSplitterRegistry.LENGTH_PREFIXED}' for segmented length-prefixed frames",
                )
            }
            val checksum = normalizedChecksum(framing)
            if (checksum != null && checksum.coverage != ChecksumCoverage.SEGMENTS) {
                errors += DefinitionValidationError(
                    "protocol.framing.checksum.coverage",
                    "must be 'segments' for a segmented codec",
                )
            }
            validateFrameLayout(
                segmented.request,
                "protocol.framing.request",
                (checksum?.width ?: 0) / 8,
                framing.splitter.maxFrameBytes,
                errors,
                checksum?.outputByteOrder,
            )
            validateFrameLayout(
                segmented.response,
                "protocol.framing.response",
                (checksum?.width ?: 0) / 8,
                framing.splitter.maxFrameBytes,
                errors,
                checksum?.outputByteOrder,
            )
        } else if (framing.codec == "segmented") {
            errors += DefinitionValidationError(
                "protocol.framing.request",
                "is required for the segmented codec",
            )
        } else {
            if (framing.header == null || framing.header !in 0..0xff) {
                errors += DefinitionValidationError("protocol.framing.header", "must fit UInt8")
            }
            if (framing.trailer == null || framing.trailer !in 0..0xff) {
                errors += DefinitionValidationError("protocol.framing.trailer", "must fit UInt8")
            }
            normalizedChecksum(framing)?.let { checksum ->
                if (checksum.coverage != ChecksumCoverage.BODY) {
                    errors += DefinitionValidationError(
                        "protocol.framing.checksum.coverage",
                        "must be 'body' for the legacy framed codec",
                    )
                }
            }
        }
        // The codec owns the shape of its own configuration: build the runtime spec here so a
        // malformed layout is rejected at validation time instead of at connection time.
        runCatching { framing.toSpec() }.onFailure { error ->
            errors += DefinitionValidationError("protocol.framing", error.message ?: "invalid framing configuration")
        }
        if (transport?.type != TransportType.RFCOMM) {
            errors += DefinitionValidationError("protocol.framing", "is only valid for an rfcomm transport")
        }
    }

    private fun validateChecksum(
        framing: FramingDefinition,
        errors: MutableList<DefinitionValidationError>,
    ) {
        val checksum = normalizedChecksum(framing, errors) ?: return
        ChecksumRegistry.validate(checksum).forEach { error ->
            errors += DefinitionValidationError(
                "protocol.framing.checksum.${error.parameter}",
                "checksum '${checksum.function}' ${error.message}",
            )
        }
    }

    private fun normalizedChecksum(
        framing: FramingDefinition,
        errors: MutableList<DefinitionValidationError>? = null,
    ): ChecksumSpec? {
        framing.checksumSpec?.let { return it }
        val legacy = framing.checksum ?: return null
        return runCatching { ChecksumSpec.legacy(legacy) }.getOrElse { error ->
            errors?.add(
                DefinitionValidationError(
                    "protocol.framing.checksum",
                    error.message ?: "invalid legacy checksum",
                ),
            )
            null
        }
    }

    private fun validateFrameLayout(
        layout: FrameLayoutSpec,
        path: String,
        checksumWidth: Int,
        maxFrameBytes: Int,
        errors: MutableList<DefinitionValidationError>,
        checksumOrder: com.Fusion.Btremix.protocol.api.Endianness? = null,
    ) {
        FrameLayoutRules.validate(layout, checksumWidth, checksumOrder).forEach { issue ->
            errors += DefinitionValidationError("$path.${issue.parameter}", issue.message)
        }
        val minimum = runCatching { layout.minimalBytes(checksumWidth) }.getOrNull()
        if (minimum == null) {
            errors += DefinitionValidationError(path, "layout cannot be resolved")
        } else if (minimum > maxFrameBytes) {
            errors += DefinitionValidationError(
                path,
                "minimum frame size $minimum exceeds maxFrameBytes $maxFrameBytes",
            )
        }
    }

    private fun validateCommandWidth(
        command: Int,
        layout: FrameLayoutSpec,
        path: String,
        errors: MutableList<DefinitionValidationError>,
    ) {
        val width = layout.commandWidthBytes ?: return
        val maximum = (1 shl (width * 8)) - 1
        if (command !in 0..maximum) {
            errors += DefinitionValidationError(path, "does not fit the ${width}-byte command segment")
        }
    }

    private fun validatePacketMatcher(
        matcher: PacketMatcherSpec,
        path: String,
        requestLayout: FrameLayoutSpec,
        responseLayout: FrameLayoutSpec,
        errors: MutableList<DefinitionValidationError>,
    ) {
        validatePacketMatcherBasic(matcher, path, errors)
        if (matcher.matchRequestSequence && requestLayout.segment(FrameSegmentRole.SEQUENCE) == null) {
            errors += DefinitionValidationError("$path.sequence", "request layout has no sequence segment")
        }
        if ((matcher.matchRequestSequence || matcher.sequence != null) &&
            responseLayout.segment(FrameSegmentRole.SEQUENCE) == null
        ) {
            errors += DefinitionValidationError("$path.sequence", "response layout has no sequence segment")
        }
        if (matcher.command != null) {
            validateCommandWidth(matcher.command, responseLayout, "$path.command", errors)
        }
        if ((matcher.payloadPrefix.isNotEmpty() || matcher.payloadTypes.isNotEmpty()) &&
            responseLayout.segment(FrameSegmentRole.PAYLOAD) == null
        ) {
            errors += DefinitionValidationError("$path", "response layout has no payload segment")
        }
    }

    private fun validatePacketMatcherBasic(
        matcher: PacketMatcherSpec,
        path: String,
        errors: MutableList<DefinitionValidationError>,
    ) {
        if (matcher.command != null && matcher.command !in 0..MAX_COMMAND) {
            errors += DefinitionValidationError("$path.command", "must fit UInt24")
        }
        if (matcher.sequence != null && matcher.sequence !in 0..0xff) {
            errors += DefinitionValidationError("$path.sequence", "must fit UInt8")
        }
        if (matcher.payloadTypes.any { it !in 0..0xff }) {
            errors += DefinitionValidationError("$path.payloadTypes", "every payload type must fit UInt8")
        }
    }

    fun requireValid(definition: LoadedDeviceDefinition): LoadedDeviceDefinition {
        val errors = validate(definition)
        if (errors.isNotEmpty()) throw DefinitionValidationException(errors)
        return definition
    }

    private fun validateMatcher(matcher: DeviceMatchRule, path: String, errors: MutableList<DefinitionValidationError>) {
        if (matcher.priority < 0) errors += DefinitionValidationError("$path.priority", "must not be negative")
        when (matcher) {
            is DeviceMatchRule.NameExact -> if (matcher.value.isBlank()) errors += DefinitionValidationError("$path.value", "must not be blank")
            is DeviceMatchRule.NamePrefix -> if (matcher.value.isBlank()) errors += DefinitionValidationError("$path.value", "must not be blank")
            is DeviceMatchRule.NameRegex -> runCatching { Regex(matcher.pattern) }
                .onFailure { errors += DefinitionValidationError("$path.value", "invalid regular expression") }
            is DeviceMatchRule.AddressRegex -> runCatching { Regex(matcher.pattern) }
                .onFailure { errors += DefinitionValidationError("$path.value", "invalid regular expression") }
            is DeviceMatchRule.ServiceUuid -> Unit
            is DeviceMatchRule.ManufacturerData -> Unit
        }
    }

    private fun validateField(field: ProtocolFieldDefinition, path: String, errors: MutableList<DefinitionValidationError>) {
        if (field.name.isBlank()) errors += DefinitionValidationError("$path.name", "must not be blank")
        if (field.length != null && field.length < 0) errors += DefinitionValidationError("$path.length", "must not be negative")
        if (field.offset < 0) errors += DefinitionValidationError("$path.offset", "must not be negative")
        when (field.type) {
            STRING, BYTES, SLICE -> if (field.length == null) errors += DefinitionValidationError("$path.length", "is required for ${field.type}")
            ENUM -> {
                if (field.enumValues.isEmpty()) errors += DefinitionValidationError("$path.enumValues", "must not be empty")
                field.enumValues.keys.filterNot { it in 0..0xff }.forEach {
                    errors += DefinitionValidationError("$path.enumValues", "key $it must fit UInt8")
                }
            }
            BIT_FIELD -> if (field.mask == null || field.mask !in 1..0xff) errors += DefinitionValidationError("$path.mask", "must fit a non-zero UInt8 mask")
            CONCAT -> if (field.parts.isEmpty()) errors += DefinitionValidationError("$path.parts", "must not be empty")
            else -> Unit
        }
        field.parts.forEachIndexed { index, part -> validateField(part, "$path.parts[$index]", errors) }
    }

    private fun validateRange(min: Double?, max: Double?, step: Double?, path: String, errors: MutableList<DefinitionValidationError>) {
        if (min != null && max != null && min > max) errors += DefinitionValidationError(path, "min must not exceed max")
        if (step != null && step <= 0.0) errors += DefinitionValidationError("$path.step", "must be positive")
    }

    private fun matchesType(value: StateValue, type: StateDefinitionType): Boolean = when (type) {
        StateDefinitionType.BOOLEAN -> value is StateValue.BooleanValue
        StateDefinitionType.INTEGER -> value is StateValue.IntValue || value is StateValue.LongValue
        StateDefinitionType.NUMBER -> value is StateValue.IntValue || value is StateValue.LongValue || value is StateValue.FloatValue || value is StateValue.DoubleValue
        StateDefinitionType.STRING, StateDefinitionType.ENUM -> value is StateValue.StringValue
        StateDefinitionType.BYTES -> value is StateValue.BytesValue
    }

    private fun validateUi(nodes: List<UiNode>, path: String, definition: LoadedDeviceDefinition, errors: MutableList<DefinitionValidationError>) {
        nodes.forEachIndexed { index, node ->
            val nodePath = "$path[$index]"
            fun checkState(key: String) { if (key !in definition.states) errors += DefinitionValidationError("$nodePath.state", "unknown state '$key'") }
            fun checkAction(id: String) { if (id !in definition.actions) errors += DefinitionValidationError("$nodePath.action", "unknown action '$id'") }
            when (node) {
                is UiNode.Column -> validateUi(node.children, "$nodePath.children", definition, errors)
                is UiNode.Section -> validateUi(node.children, "$nodePath.children", definition, errors)
                is UiNode.Text -> node.state?.let(::checkState)
                is UiNode.Value -> checkState(node.state)
                is UiNode.Switch -> { checkState(node.state); checkAction(node.action) }
                is UiNode.Slider -> { checkState(node.state); checkAction(node.action) }
                is UiNode.Button -> checkAction(node.action)
                is UiNode.Segmented -> { checkState(node.state); checkAction(node.action) }
                is UiNode.Progress -> checkState(node.state)
            }
        }
    }
}

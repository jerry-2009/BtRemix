package com.Fusion.Btremix.definition.validator

import com.Fusion.Btremix.definition.api.DeviceMatchRule
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.ProtocolFieldDefinition
import com.Fusion.Btremix.definition.api.ProtocolFieldType
import com.Fusion.Btremix.definition.api.ProtocolFieldType.*
import com.Fusion.Btremix.definition.api.*
import com.Fusion.Btremix.device.runtime.StateValue
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
        if (manifest.matchers.isEmpty()) errors += DefinitionValidationError("manifest.matchers", "must contain at least one rule")
        manifest.matchers.forEachIndexed { index, matcher -> validateMatcher(matcher, "manifest.matchers[$index]", errors) }
        definition.melody?.let { melody -> validateMelody(melody, manifest, errors) }

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
        definition.protocol.initialize.forEachIndexed { index, name ->
            if (manifest.schemaVersion < DefinitionSchema.VERSION_STREAM) {
                errors += DefinitionValidationError("protocol.initialize[$index]", "requires manifest.schemaVersion ${DefinitionSchema.VERSION_STREAM}")
            }
            if (name !in definition.protocol.transactions) {
                errors += DefinitionValidationError("protocol.initialize[$index]", "unknown transaction '$name'")
            }
        }
        if (definition.protocol.transport?.type == TransportType.RFCOMM) {
            definition.actions.values.filter { it.script != null }.forEach { action ->
                errors += DefinitionValidationError(
                    "actions.${action.id}.script",
                    "is not supported over an rfcomm transport; use 'transaction'",
                )
            }
        }
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
            if (transaction.requestCommand !in 0..0xff) errors += DefinitionValidationError("protocol.transactions.$name.requestCommand", "must fit UInt8")
            if (transaction.expectedCommand != null && transaction.expectedCommand !in 0..0xff) errors += DefinitionValidationError("protocol.transactions.$name.expectedCommand", "must fit UInt8")
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
        }
        return errors
    }

    private fun validateMelody(
        melody: MelodySectionDefinition,
        manifest: DefinitionManifest,
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
        // All three lists name official panel keys, so they share the blank / namespace guard
        // (HANDOFF_MELODY_M4_PLAN.md §3 M4.0 A-2).
        validateMelodyKeys(melody.panel.hideSections, "melody.panel.hideSections", errors)
        validateMelodyKeys(melody.panel.hideKeys, "melody.panel.hideKeys", errors)
        validateMelodyKeys(melody.panel.greyKeys, "melody.panel.greyKeys", errors)
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
            if (stream) {
                errors += DefinitionValidationError("$path.notify", "an rfcomm transport requires 'payloadType'")
            }
            validateUuid(notify.service, "$path.notify.service", errors)
            validateUuid(notify.characteristic, "$path.notify.characteristic", errors)
            if (notify.condition != null) {
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
        // The codec owns the shape of its own configuration: build the runtime spec here so a
        // malformed layout is rejected at validation time instead of at connection time.
        runCatching { framing.toSpec() }.onFailure { error ->
            errors += DefinitionValidationError("protocol.framing", error.message ?: "invalid framing configuration")
        }
        if (transport?.type != TransportType.RFCOMM) {
            errors += DefinitionValidationError("protocol.framing", "is only valid for an rfcomm transport")
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

package com.Fusion.Btremix.definition.validator

import com.Fusion.Btremix.definition.api.DeviceMatchRule
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.ProtocolFieldDefinition
import com.Fusion.Btremix.definition.api.ProtocolFieldType
import com.Fusion.Btremix.definition.api.ProtocolFieldType.*

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
        if (manifest.matchers.isEmpty()) errors += DefinitionValidationError("manifest.matchers", "must contain at least one rule")
        manifest.matchers.forEachIndexed { index, matcher -> validateMatcher(matcher, "manifest.matchers[$index]", errors) }

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
            if (transaction.requestMessage != null && transaction.requestMessage !in definition.protocol.messages) {
                errors += DefinitionValidationError("protocol.transactions.$name.requestMessage", "unknown message '${transaction.requestMessage}'")
            }
            if (transaction.retries < 0) errors += DefinitionValidationError("protocol.transactions.$name.retries", "must not be negative")
            if (transaction.timeout != null && !transaction.timeout.isPositive()) errors += DefinitionValidationError("protocol.transactions.$name.timeout", "must be positive")
        }
        return errors
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
}

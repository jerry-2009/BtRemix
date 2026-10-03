package com.Fusion.Btremix.definition.matcher

import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.core.bluetooth.api.BleService
import com.Fusion.Btremix.definition.api.DeviceMatchRule
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import java.util.UUID

data class DefinitionMatch(
    val definition: LoadedDeviceDefinition,
    val priority: Int,
    val rule: DeviceMatchRule,
)

/** Chooses the highest-priority matching rule and preserves declaration order on ties. */
object DefinitionMatcher {
    fun matches(definition: LoadedDeviceDefinition, scan: BleScanResult): Boolean =
        definition.manifest.matchers.any { it.matches(scan) }

    fun rank(definition: LoadedDeviceDefinition, scan: BleScanResult): DefinitionMatch? =
        definition.manifest.matchers.asSequence()
            .filter { it.matches(scan) }
            .map { DefinitionMatch(definition, it.priority, it) }
            .maxByOrNull { it.priority }

    /**
     * Connect-time match: a device whose advertised name did not match can still be recognised once
     * its GATT services are known.
     *
     * Explicit `serviceUuid` matchers win with their declared priority; otherwise a definition that
     * declares the discovered service anywhere (protocol transport or a notification binding) is
     * matched with priority 0 so a scan-time match would always outrank it.
     */
    fun rankServices(definition: LoadedDeviceDefinition, services: List<BleService>): DefinitionMatch? {
        val available = services.mapTo(mutableSetOf()) { it.uuid }
        val explicit = definition.manifest.matchers
            .filterIsInstance<DeviceMatchRule.ServiceUuid>()
            .filter { it.uuid in available }
            .maxByOrNull { it.priority }
        if (explicit != null) return DefinitionMatch(definition, explicit.priority, explicit)
        val declared = declaredServiceUuids(definition).firstOrNull { it in available } ?: return null
        return DefinitionMatch(definition, IMPLICIT_SERVICE_PRIORITY, DeviceMatchRule.ServiceUuid(declared, IMPLICIT_SERVICE_PRIORITY))
    }

    /** Service UUIDs a definition needs at runtime, independent of its advertised matchers. */
    fun declaredServiceUuids(definition: LoadedDeviceDefinition): Set<UUID> = buildSet {
        definition.protocol.transport?.service?.let { runCatching { UUID.fromString(it) }.getOrNull()?.let(::add) }
        definition.states.values.forEach { state ->
            state.notify?.service?.let { runCatching { UUID.fromString(it) }.getOrNull()?.let(::add) }
        }
    }

    const val IMPLICIT_SERVICE_PRIORITY: Int = 0

    fun find(
        definitions: Iterable<LoadedDeviceDefinition>,
        scan: BleScanResult,
    ): LoadedDeviceDefinition? = definitions.asSequence()
        .mapNotNull { rank(it, scan) }
        .maxByOrNull { it.priority }
        ?.definition

    /** Human-readable description of a rule, used by the explorer to explain a match. */
    fun label(rule: DeviceMatchRule): String = when (rule) {
        is DeviceMatchRule.NameExact -> "name == \"${rule.value}\""
        is DeviceMatchRule.NamePrefix -> "name starts with \"${rule.value}\""
        is DeviceMatchRule.NameRegex -> "name matches /${rule.pattern}/"
        is DeviceMatchRule.AddressRegex -> "address matches /${rule.pattern}/"
        is DeviceMatchRule.ServiceUuid -> "advertises service ${rule.uuid}"
        is DeviceMatchRule.ManufacturerData -> "manufacturer data 0x%04X".format(rule.companyId)
    }
}

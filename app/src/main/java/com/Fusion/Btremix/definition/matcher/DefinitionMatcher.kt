package com.Fusion.Btremix.definition.matcher

import com.Fusion.Btremix.core.bluetooth.api.BleScanResult
import com.Fusion.Btremix.definition.api.DeviceMatchRule
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition

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

    fun find(
        definitions: Iterable<LoadedDeviceDefinition>,
        scan: BleScanResult,
    ): LoadedDeviceDefinition? = definitions.asSequence()
        .mapNotNull { rank(it, scan) }
        .maxByOrNull { it.priority }
        ?.definition
}

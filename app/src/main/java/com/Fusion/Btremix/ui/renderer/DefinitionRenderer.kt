package com.Fusion.Btremix.ui.renderer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Fusion.Btremix.definition.api.LoadedDeviceDefinition
import com.Fusion.Btremix.definition.api.StateDefinitionType
import com.Fusion.Btremix.definition.api.UiNode
import com.Fusion.Btremix.device.runtime.DeviceAction
import com.Fusion.Btremix.device.runtime.StateEntry
import com.Fusion.Btremix.device.runtime.StateValue

/** Renders a validated definition without knowing BLE, packets, or Android GATT. */
@Composable
fun DefinitionDevicePage(
    definition: LoadedDeviceDefinition,
    state: Map<String, StateEntry>,
    onAction: (DeviceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(definition.ui.title ?: definition.displayName, style = MaterialTheme.typography.headlineSmall)
        definition.ui.children.forEach { node -> DefinitionNode(node, definition, state, onAction) }
    }
}

@Composable
private fun DefinitionNode(node: UiNode, definition: LoadedDeviceDefinition, state: Map<String, StateEntry>, onAction: (DeviceAction) -> Unit) {
    when (node) {
        is UiNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { node.children.forEach { DefinitionNode(it, definition, state, onAction) } }
        is UiNode.Section -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(node.title, style = MaterialTheme.typography.titleMedium)
                node.children.forEach { DefinitionNode(it, definition, state, onAction) }
            }
        }
        is UiNode.Text -> Text(node.text ?: node.state?.let { displayValue(state[it]?.value) }.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        is UiNode.Value -> {
            val entry = state[node.state]
            val definitionState = definition.states[node.state]
            Column {
                Text(definitionState?.displayName ?: node.state, style = MaterialTheme.typography.labelLarge)
                Text(displayValue(entry?.value) + (definitionState?.unit?.let { " $it" } ?: ""), style = MaterialTheme.typography.titleMedium)
            }
        }
        is UiNode.Switch -> {
            val checked = (state[node.state]?.value as? StateValue.BooleanValue)?.value ?: false
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(definition.states[node.state]?.displayName ?: node.state)
                Switch(checked = checked, onCheckedChange = { onAction(DeviceAction(node.action, mapOf(actionParameter(definition, node.action) to StateValue.BooleanValue(it)))) })
            }
        }
        is UiNode.Slider -> {
            val model = definition.states[node.state]
            val value = numericValue(state[node.state]?.value).toFloat()
            val range = (model?.min?.toFloat() ?: 0f)..(model?.max?.toFloat() ?: 100f)
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(model?.displayName ?: node.state)
                    Text(displayValue(state[node.state]?.value))
                }
                Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = {
                    onAction(DeviceAction(node.action, mapOf(actionParameter(definition, node.action) to numericArgument(model?.type, it.toDouble()))))
                }, valueRange = range, steps = steps(model?.step, range))
            }
        }
        is UiNode.Button -> Button(onClick = { onAction(DeviceAction(node.action, node.args)) }, modifier = Modifier.fillMaxWidth()) { Text(node.label) }
        is UiNode.Segmented -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(definition.states[node.state]?.displayName ?: node.state, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                node.options.forEach { option ->
                    val selected = (state[node.state]?.value as? StateValue.StringValue)?.value == option
                    Surface(onClick = { onAction(DeviceAction(node.action, mapOf(actionParameter(definition, node.action) to StateValue.StringValue(option)))) }, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(option, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        }
        is UiNode.Progress -> LinearProgressIndicator(progress = { numericValue(state[node.state]?.value).toFloat().coerceIn(0f, 100f) / 100f }, modifier = Modifier.fillMaxWidth())
    }
}

private fun displayValue(value: StateValue?): String = when (value) {
    null -> "--"
    is StateValue.BooleanValue -> if (value.value) "On" else "Off"
    is StateValue.BytesValue -> value.value.joinToString(" ") { "%02X".format(it) }
    is StateValue.ListValue -> value.value.joinToString()
    is StateValue.MapValue -> value.value.toString()
    else -> value.toString().substringAfter("(").removeSuffix(")")
}

private fun numericValue(value: StateValue?): Double = when (value) {
    is StateValue.IntValue -> value.value.toDouble()
    is StateValue.LongValue -> value.value.toDouble()
    is StateValue.FloatValue -> value.value.toDouble()
    is StateValue.DoubleValue -> value.value
    else -> 0.0
}

private fun actionParameter(definition: LoadedDeviceDefinition, action: String): String = definition.actions[action]?.parameters?.firstOrNull()?.name ?: "value"

private fun numericArgument(type: StateDefinitionType?, value: Double): StateValue = when (type) {
    StateDefinitionType.INTEGER -> StateValue.IntValue(value.toInt())
    else -> StateValue.DoubleValue(value)
}

private fun steps(step: Double?, range: ClosedFloatingPointRange<Float>): Int = step?.takeIf { it > 0 }?.let { ((range.endInclusive - range.start) / it).toInt().coerceAtLeast(0) - 1 }?.coerceAtLeast(0) ?: 0

package com.Fusion.Btremix.melody.projection

import com.Fusion.Btremix.definition.api.MelodyProductId
import com.Fusion.Btremix.definition.api.MelodySupportDefinition
import com.Fusion.Btremix.definition.json.JsonParser
import com.Fusion.Btremix.definition.json.JsonValue
import com.Fusion.Btremix.definition.json.JsonWriter
import com.Fusion.Btremix.melody.config.MelodyManagedDevice

/** Supplies the whitelist template JSON for a Definition-declared path; `null` when unreadable. */
fun interface MelodyTemplateSource {
    fun read(path: String): String?
}

/**
 * The projection envelope plus the one diagnostic the binder logs
 * (`melody.whitelist.template_missing`). Kept separate from the JSON so the caller can report the
 * degradation without re-parsing or guessing.
 */
data class MelodyProjection(
    val json: String,
    val templateFound: Boolean,
)

/**
 * Turns a managed device into the envelope the Melody hook consumes (MELODY_BRIDGE_SPEC §5.4, §12 M3;
 * HANDOFF_MELODY_M3_PLAN.md §2.2 / §4 M3.1).
 *
 * The host's `WhitelistConfigDTO.Function` has 135 fields and inventing them is exactly the risk §5.4
 * calls out, so the neutral template captured in M3.-1 keeps the structure and this builder only
 * overrides the *identity* fields. Capability mapping (Definition capability -> `Function` switch) is
 * M3.2; until then every switch stays at the template's neutral, disabled value (decision M3-D7).
 *
 * Resolution order for the product id follows M3-D2: caller-provided instance value, then the
 * Definition's `support.productId`, then the template's neutral `null`.
 */
class MelodyProjectionBuilder(private val templates: MelodyTemplateSource) {

    fun build(device: MelodyManagedDevice, instanceProductId: String? = null): String =
        project(device, instanceProductId).json

    fun project(device: MelodyManagedDevice, instanceProductId: String? = null): MelodyProjection {
        val support = device.melody.support
        val templatePath = support.templateWhitelist ?: MelodySupportDefinition.DEFAULT_TEMPLATE_WHITELIST
        val parsed = templates.read(templatePath)?.let { text ->
            runCatching { JsonParser.parse(text) as? JsonValue.Object }.getOrNull()
        }
        val whitelist = applyIdentity(parsed ?: JsonValue.Object(emptyMap()), device, instanceProductId)
        val envelope = JsonValue.Object(
            linkedMapOf(
                "version" to JsonValue.NumberValue(ENVELOPE_VERSION.toString()),
                "mac" to JsonValue.StringValue(device.mac),
                "definition" to JsonValue.Object(
                    linkedMapOf(
                        "id" to JsonValue.StringValue(device.definition.manifest.id),
                        "version" to JsonValue.StringValue(device.definition.manifest.version),
                    ),
                ),
                "whitelist" to whitelist,
                "panel" to panel(device),
            ),
        )
        return MelodyProjection(json = JsonWriter.write(envelope), templateFound = parsed != null)
    }

    private fun applyIdentity(
        template: JsonValue.Object,
        device: MelodyManagedDevice,
        instanceProductId: String?,
    ): JsonValue.Object {
        val support = device.melody.support
        val fields = LinkedHashMap(template.values)
        fields["name"] = JsonValue.StringValue(support.name)
        fields["brand"] = JsonValue.StringValue(support.brand ?: support.name)
        fields["supportSpp"] = JsonValue.BooleanValue(support.supportSpp)
        support.uuid?.let { fields["uuid"] = JsonValue.StringValue(it) }
        val productId = instanceProductId ?: support.productId
        productId
            ?.let(MelodyProductId::normalizeOrNull)
            ?.toLongOrNull()
            ?.let(MelodyProductId::toHexId)
            ?.let { fields["id"] = JsonValue.StringValue(it) }
        return JsonValue.Object(fields)
    }

    private fun panel(device: MelodyManagedDevice): JsonValue.Object {
        val panel = device.melody.panel
        return JsonValue.Object(
            linkedMapOf(
                "sectionTitle" to JsonValue.StringValue(
                    panel.sectionTitle ?: device.definition.manifest.displayName,
                ),
                "hideSections" to strings(panel.hideSections),
                "hideKeys" to strings(panel.hideKeys),
                "greyKeys" to strings(panel.greyKeys),
            ),
        )
    }

    private fun strings(values: List<String>): JsonValue =
        JsonValue.Array(values.map(JsonValue::StringValue))

    companion object {
        /** Envelope wire version; a bump invalidates any host-side cache (M3 plan §2.2). */
        const val ENVELOPE_VERSION: Int = 1
    }
}

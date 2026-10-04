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

/** Which template the projection was actually composed from (HANDOFF_MELODY_M3_PLAN.md §4 M3.2). */
enum class MelodyTemplateUse {
    /** The Definition's `support.templateWhitelist` (or the default when none was declared). */
    DECLARED,

    /** The declared path was missing/corrupt, so the built-in APK asset was used instead. */
    FALLBACK,

    /** No usable template at all: the Spec §5.4 minimal field set was synthesized. */
    MINIMAL,
}

/**
 * The projection envelope plus the diagnostics the binder logs (`melody.whitelist.template_missing`,
 * and the template source on `melody.projection.built`). Kept separate from the JSON so the caller can
 * report the degradation without re-parsing or guessing.
 */
data class MelodyProjection(
    val json: String,
    val templateUse: MelodyTemplateUse,
) {
    /** A structurally complete template (declared or built-in fallback) was used. */
    val templateFound: Boolean get() = templateUse != MelodyTemplateUse.MINIMAL

    /** The declared path could not be used; the builder fell back and/or degraded. */
    val templateMissing: Boolean get() = templateUse != MelodyTemplateUse.DECLARED

    /** No template at all: the whitelist is the minimal field set (Spec §5.4 item 4). */
    val degraded: Boolean get() = templateUse == MelodyTemplateUse.MINIMAL
}

/**
 * Turns a managed device into the envelope the Melody hook consumes (MELODY_BRIDGE_SPEC §5.4, §12 M3;
 * HANDOFF_MELODY_M3_PLAN.md §2.2 / §4 M3.1 / §4 M3.2).
 *
 * The host's `WhitelistConfigDTO.Function` has 135 fields and inventing them is exactly the risk §5.4
 * calls out, so the neutral template captured in M3.-1 keeps the structure and this builder only
 * overrides the *identity* fields plus the capability switches enabled by [MelodyCapabilityMap].
 *
 * Template handling follows §5.4 item 4 (M3-D4): the Definition points at an APK asset, and when that
 * asset is missing or corrupt the builder falls back to the built-in template and reports
 * [MelodyTemplateUse.FALLBACK]; when even that is unusable it degrades to the minimal field set and
 * reports [MelodyTemplateUse.MINIMAL]. Neither case is silent and neither throws.
 *
 * Resolution order for the product id follows M3-D2: caller-provided instance value, then the
 * Definition's `support.productId`, then the template's neutral `null`.
 */
class MelodyProjectionBuilder(
    private val templates: MelodyTemplateSource,
    /**
     * Capability switches this builder is allowed to turn on. Defaults to
     * [MelodyCapabilityMap.ENABLED_IN_M4] (the M4.1 set); passing [MelodyCapabilityMap.ENABLED_IN_M3]
     * reproduces the neutral M3-D7 envelope, which the JVM tests pin.
     */
    private val enabledCapabilities: Set<String> = MelodyCapabilityMap.ENABLED_IN_M4,
    /**
     * Raw `WhitelistConfigDTO$Function` fields forced on top of the rule table, bypassing
     * `providedBy` gating. **Debug builds only** (M4.1 capability-location experiment,
     * [MelodyCapabilityDebug]); empty in release, where the rule table is the only writer.
     */
    private val capabilityOverrides: Map<String, JsonValue> = emptyMap(),
) {

    fun build(device: MelodyManagedDevice, instanceProductId: String? = null): String =
        project(device, instanceProductId).json

    fun project(device: MelodyManagedDevice, instanceProductId: String? = null): MelodyProjection {
        val declaredPath =
            device.melody.support.templateWhitelist ?: MelodySupportDefinition.DEFAULT_TEMPLATE_WHITELIST
        val declared = readTemplate(declaredPath)
        val fallback = if (declared == null && declaredPath != MelodySupportDefinition.DEFAULT_TEMPLATE_WHITELIST) {
            readTemplate(MelodySupportDefinition.DEFAULT_TEMPLATE_WHITELIST)
        } else {
            null
        }
        val template = declared ?: fallback
        val templateUse = when {
            declared != null -> MelodyTemplateUse.DECLARED
            fallback != null -> MelodyTemplateUse.FALLBACK
            else -> MelodyTemplateUse.MINIMAL
        }
        val whitelist = template
            ?.let { applyTemplate(it, device, instanceProductId) }
            ?: minimalWhitelist(device, instanceProductId)
        val envelope = JsonValue.Object(
            linkedMapOf(
                "version" to JsonValue.NumberValue(ENVELOPE_VERSION.toString()),
                "mac" to JsonValue.StringValue(device.mac),
                "definition" to JsonValue.Object(
                    linkedMapOf(
                        "id" to JsonValue.StringValue(device.definition.manifest.id),
                        "version" to JsonValue.StringValue(device.definition.manifest.version),
                        // M3.4: the DeviceInfo side needs the integer form factor and the transport
                        // switch; both belong to us, so they travel in our own node instead of being
                        // smuggled into the official WhitelistConfigDTO shape.
                        "productType" to JsonValue.NumberValue(device.melody.support.productType.toString()),
                        "suppressTransport" to JsonValue.BooleanValue(device.melody.support.suppressMelodyTransport),
                    ),
                ),
                "whitelist" to whitelist,
                "panel" to panel(device),
            ),
        )
        return MelodyProjection(json = JsonWriter.write(envelope), templateUse = templateUse)
    }

    /**
     * A template counts as usable only when it parses to an object that actually carries the
     * `Function` field set: a truncated-but-syntactically-valid file must not silently degrade the
     * capability handling (Spec §5.4 item 4).
     */
    private fun readTemplate(path: String): JsonValue.Object? {
        val parsed = templates.read(path)?.let { text ->
            runCatching { JsonParser.parse(text) as? JsonValue.Object }.getOrNull()
        } ?: return null
        val function = parsed.values["function"] as? JsonValue.Object ?: return null
        return if (function.values.isEmpty()) null else parsed
    }

    private fun applyTemplate(
        template: JsonValue.Object,
        device: MelodyManagedDevice,
        instanceProductId: String?,
    ): JsonValue.Object {
        val fields = LinkedHashMap(template.values)
        val templateId = (template.values["id"] as? JsonValue.StringValue)?.value
        applyIdentity(fields, device, instanceProductId, templateId)
        (template.values["function"] as? JsonValue.Object)?.let { function ->
            val switchable = LinkedHashMap(function.values)
            functionOverrides(device).forEach { (key, value) ->
                switchable[key] = value
            }
            fields["function"] = JsonValue.Object(switchable)
        }
        return JsonValue.Object(fields)
    }

    /**
     * The capability writes for [device]: the rule table first, then the M4.1 debug override. The
     * override intentionally wins, so one experiment round can contradict the shipped rules.
     */
    private fun functionOverrides(device: MelodyManagedDevice): Map<String, JsonValue> =
        if (capabilityOverrides.isEmpty()) {
            MelodyCapabilityMap.overrides(device.definition, enabledCapabilities)
        } else {
            LinkedHashMap<String, JsonValue>()
                .apply { putAll(MelodyCapabilityMap.overrides(device.definition, enabledCapabilities)) }
                .apply { putAll(capabilityOverrides) }
        }

    /**
     * Spec §5.4 item 4 fallback: keep the top-level `WhitelistConfigDTO` shape the host expects, but
     * leave `function` at the structural parameters we know (`batteryRadix`) instead of inventing the
     * 135-key capability table. The caller logs the degradation, so the gap is never silent.
     */
    private fun minimalWhitelist(
        device: MelodyManagedDevice,
        instanceProductId: String?,
    ): JsonValue.Object {
        val fields = linkedMapOf<String, JsonValue>(
            "brand" to JsonValue.NullValue,
            "btDelayReport" to JsonValue.NullValue,
            "children" to JsonValue.NullValue,
            "coreFrom" to JsonValue.NumberValue("0"),
            "defaultColor" to JsonValue.NumberValue("-1"),
            "function" to JsonValue.Object(minimalFunction(device)),
            "fuzzyMatchName" to JsonValue.BooleanValue(false),
            "id" to JsonValue.NullValue,
            "minRssi" to JsonValue.NumberValue("50"),
            "minVersion" to JsonValue.NumberValue("0"),
            "name" to JsonValue.NullValue,
            "opsPodsVersion" to JsonValue.NumberValue("0"),
            "podsVersion" to JsonValue.NumberValue("0"),
            "protocolType" to JsonValue.NullValue,
            "rssi" to JsonValue.NullValue,
            "supportRlmDeviceFunction" to JsonValue.BooleanValue(false),
            "supportSpp" to JsonValue.BooleanValue(false),
            "type" to JsonValue.NullValue,
            "uuid" to JsonValue.NullValue,
        )
        applyIdentity(fields, device, instanceProductId, templateId = null)
        return JsonValue.Object(fields)
    }

    /** The structural parameters that stay valid even without the capability table (`batteryRadix`). */
    private fun minimalFunction(device: MelodyManagedDevice): Map<String, JsonValue> {
        val function = linkedMapOf<String, JsonValue>("batteryRadix" to JsonValue.NumberValue("10"))
        functionOverrides(device).forEach { (key, value) ->
            function[key] = value
        }
        return function
    }

    /**
     * Identity overlay shared by the template and minimal paths (Spec §5.4 item 2): `name`, `brand`,
     * `type`, `uuid`, `supportSpp` and the product id. Optional fields only overwrite when the
     * Definition (or the caller's instance value) actually provides them.
     */
    private fun applyIdentity(
        fields: MutableMap<String, JsonValue>,
        device: MelodyManagedDevice,
        instanceProductId: String?,
        templateId: String?,
    ) {
        val support = device.melody.support
        fields["name"] = JsonValue.StringValue(support.name)
        fields["brand"] = JsonValue.StringValue(support.brand ?: support.name)
        fields["supportSpp"] = JsonValue.BooleanValue(support.supportSpp)
        support.uuid?.let { fields["uuid"] = JsonValue.StringValue(it) }
        MelodyProductType.officialType(support.productType)
            ?.let { fields["type"] = JsonValue.StringValue(it) }
        resolveHexId(instanceProductId, support.productId, templateId)
            ?.let { fields["id"] = JsonValue.StringValue(it) }
    }

    /** M3-D2 order: instance value -> Definition `support.productId` -> template's neutral value. */
    private fun resolveHexId(instanceProductId: String?, definitionProductId: String?, templateId: String?): String? {
        val candidate = instanceProductId ?: definitionProductId ?: return templateId
        val normalized = MelodyProductId.normalizeOrNull(candidate) ?: return templateId
        return normalized.toLongOrNull()?.let(MelodyProductId::toHexId) ?: templateId
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

package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.definition.api.MelodyPanelDefinition
import com.Fusion.Btremix.definition.api.MelodyAncStrengthDefinition
import com.Fusion.Btremix.definition.api.MelodyAncStrengthLevel
import com.Fusion.Btremix.definition.api.MelodyAncMode
import com.Fusion.Btremix.definition.api.MelodyProductId
import com.Fusion.Btremix.definition.json.JsonParser
import com.Fusion.Btremix.definition.json.JsonValue
import com.Fusion.Btremix.definition.json.JsonWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A provider cursor materialised as plain values (column names + rows aligned to them).
 *
 * The Android layer turns a `Cursor` into this, the merge below is pure Kotlin, and the Android layer
 * turns the result back into a `MatrixCursor`. That split keeps the risky part — what exactly the host
 * sees for `find_whitelist` / `ears_whitelist` / `all_whitelist` / `whitelist_content` — unit tested
 * on the JVM instead of only observable on a phone (M3.3 plan §5.1).
 */
data class MelodyInjectionTable(val columns: List<String>, val rows: List<List<Any?>>)

/**
 * The identity the injection needs, derived from the projection envelope's `whitelist` node (which is
 * a complete `WhitelistConfigDTO` JSON — `MELODY_BRIDGE_SPEC` §5.4).
 *
 * Both encodings of the product id are kept because the host itself mixes them: `find_whitelist`
 * returns `WhitelistConfigDTO.getId()` (uppercase hex) while `ears_whitelist.product_id` and the
 * `find_whitelist` selection argument use the decimal form (`COLOROS_MELODY_ANALYSIS.md` §K2,
 * `HANDOFF_MELODY_M3_PLAN.md` §4 M3.0).
 */
data class MelodyWhitelistIdentity(
    val mac: String,
    val name: String,
    /** `WhitelistConfigDTO.id`: uppercase, zero padded to 6 (e.g. `000CE0`). */
    val hexId: String,
    /** Decimal form of [hexId] (e.g. `3296`). */
    val decimalId: String,
    /** Shape token `T1` / `T2` / `N` / `O1`, or `null` when the template stayed neutral. */
    val type: String?,
    val uuid: String?,
    val supportSpp: Boolean,
    /** The `whitelist` node re-serialised; this is what the host parses for a `content` column. */
    val whitelistJson: String,
)

/**
 * The `melody.anc` node of an envelope (M4.3b, decision D-12): the host render version and the mode
 * table the client projects into `EarphoneDTO.getNoiseReductionModeIndex()` and into the
 * `ModeItem.name` label hook.
 *
 * It is deliberately all-or-nothing: a node that is absent or malformed reads as `null`, and the
 * caller then leaves the host's own ANC state and texts untouched (fail-open, as everywhere else).
 */
data class MelodyAncPolicy(
    val uiVersion: Int,
    val modes: List<MelodyAncMode>,
    /**
     * The Definition's `ancLevel`-style strength mapped onto the host's own three-position slider
     * (M4.3b D-14), or `null` when the Definition has none.
     */
    val strength: MelodyAncStrengthDefinition? = null,
) {
    val isEmpty: Boolean get() = modes.isEmpty()

    /** The D-12 label for a host `ModeItem.id` (`String.valueOf(modeType)`), or `null` to leave it. */
    fun labelOf(modeType: Int): String? =
        modes.firstOrNull { it.modeType == modeType }?.label?.takeIf { it.isNotBlank() }

    companion object {
        /** Cached answer for "this envelope has no usable `anc` node". */
        val NONE: MelodyAncPolicy = MelodyAncPolicy(uiVersion = 0, modes = emptyList())
    }
}

/**
 * The host's fixed ANC cell order (`e9.r.a(uiVersion)`, `NoiseReductionItem.updateActionView`).
 *
 * `ModeItem.id` is the cell *position* the host assigned while building the row
 * (`NoiseReductionItem.createModeItem` writes `String.valueOf(counter)` into it), **not** the
 * modeType - so the D-12 label hook has to rewrite the titles positionally in this order instead of
 * looking a modeType up by id. The host only adds a cell when its injected mode table carries that
 * `modeType`, so the caller filters this order by [modes].
 *
 * `ui=1 -> [4,3,5,10,1,2,6]`, `ui=2 -> [4,3,5,10,2,6,1]` (17.6.3, `Le9.r.a`).
 */
object MelodyAncRenderOrder {

    private val UI_1: List<Int> = listOf(4, 3, 5, 10, 1, 2, 6)
    private val UI_2: List<Int> = listOf(4, 3, 5, 10, 2, 6, 1)

    /** The `modeType`s the host renders, in display order, for an injected table of [modes]. */
    fun of(uiVersion: Int, modes: List<MelodyAncMode>): List<Int> {
        val order = if (uiVersion == 2) UI_2 else UI_1
        return order.filter { modeType -> modes.any { it.modeType == modeType } }
    }
}

/**
 * Synthesises our row into the host's own whitelist answers (M3-D4: the official results stay, we only
 * append; M3-D7: the capability table is whatever the template produced).
 *
 * Everything here is a pure function over [MelodyInjectionTable]; the corresponding Android hook
 * (`melody/hook/injection/`) only reads/writes cursors and decides *whether* a query is ours.
 */
object MelodyProviderMerge {

    /** Columns the host declares when it answers `find_whitelist` with a `MatrixCursor` (§B). */
    val FIND_WHITELIST_COLUMNS: List<String> = listOf("name", "product_id", "content")

    /** Columns observed for the `ears_whitelist` snapshot; `type` appears only when requested. */
    val EARS_WHITELIST_COLUMNS: List<String> = listOf("name", "product_id", "support_wear_check")

    val ALL_WHITELIST_COLUMNS: List<String> = listOf("content")
    val WHITELIST_CONTENT_COLUMNS: List<String> = listOf("content")
    val WEAR_COLUMNS: List<String> = listOf("address", "both_in_ear")

    /** Same cap the whitelist export uses; a runaway merge is skipped and reported instead of built. */
    const val MAX_MERGE_BYTES: Int = 2 * 1024 * 1024

    /** The columns to use when the host produced no cursor at all (the `find_whitelist` miss case). */
    fun defaultColumns(path: MelodyQueryPath): List<String> = when (path) {
        MelodyQueryPath.FIND_WHITELIST -> FIND_WHITELIST_COLUMNS
        MelodyQueryPath.EARS_WHITELIST -> EARS_WHITELIST_COLUMNS
        MelodyQueryPath.ALL_WHITELIST -> ALL_WHITELIST_COLUMNS
        MelodyQueryPath.WHITELIST_CONTENT -> WHITELIST_CONTENT_COLUMNS
        MelodyQueryPath.EARPHONE_BOTH_IN_EAR,
        MelodyQueryPath.ACTIVE_EARPHONE_BOTH_IN_EAR,
        -> WEAR_COLUMNS
    }

    /** Reads the identity out of an envelope; `null` when it is missing, truncated or not an envelope. */
    fun identityOf(envelopeJson: String?): MelodyWhitelistIdentity? {
        val envelope = envelopeJson
            ?.let { runCatching { JsonParser.parse(it) as? JsonValue.Object }.getOrNull() }
            ?: return null
        val whitelist = envelope.values["whitelist"] as? JsonValue.Object ?: return null
        val name = (whitelist.values["name"] as? JsonValue.StringValue)?.value
            ?.takeIf(String::isNotBlank) ?: return null
        val hexId = (whitelist.values["id"] as? JsonValue.StringValue)?.value
            ?.takeIf(String::isNotBlank) ?: return null
        // `WhitelistConfigDTO.id` is always hexadecimal, and a bare all-digit token would be read as
        // decimal by `normalizeOrNull` (`06F010` -> 454672 but `060412` -> 60412); the explicit `0x`
        // prefix is what M3.0 settled on for exactly this trap.
        val decimalId = MelodyProductId.normalizeOrNull("0x$hexId") ?: return null
        return MelodyWhitelistIdentity(
            mac = (envelope.values["mac"] as? JsonValue.StringValue)?.value.orEmpty(),
            name = name,
            hexId = hexId.uppercase(),
            decimalId = decimalId,
            type = (whitelist.values["type"] as? JsonValue.StringValue)?.value,
            uuid = (whitelist.values["uuid"] as? JsonValue.StringValue)?.value,
            supportSpp = (whitelist.values["supportSpp"] as? JsonValue.BooleanValue)?.value ?: false,
            whitelistJson = JsonWriter.write(whitelist),
        )
    }

    /**
     * Reads the per-device policy M3.4 needs: the integer `DeviceInfo.mProductType` and the
     * `melody.support.suppressMelodyTransport` switch (`MelodyDevicePolicy`).
     *
     * Both live in the envelope's own `definition` node, so an envelope written before M3.4 (or a
     * truncated one) falls back to the neutral policy — which suppresses transport, the safe default.
     */
    fun policyOf(envelopeJson: String?): MelodyDevicePolicy {
        val envelope = envelopeJson
            ?.let { runCatching { JsonParser.parse(it) as? JsonValue.Object }.getOrNull() }
            ?: return MelodyDevicePolicy.NEUTRAL
        val definition = envelope.values["definition"] as? JsonValue.Object
            ?: return MelodyDevicePolicy.NEUTRAL
        val productType = (definition.values["productType"] as? JsonValue.NumberValue)
            ?.raw?.toIntOrNull()
            ?: MelodyDevicePolicy.NEUTRAL.productType
        val suppress = (definition.values["suppressTransport"] as? JsonValue.BooleanValue)?.value
            ?: MelodyDevicePolicy.NEUTRAL.suppressTransport
        return MelodyDevicePolicy(productType = productType, suppressTransport = suppress)
    }

    /**
     * Reads the optional `anc` node of an envelope (M4.3b D-12/D-14). `null` when the node is missing,
     * not an object, has no usable `modes` table or any mode is malformed - the client then projects
     * nothing (the host keeps its own ANC index and its own mode texts). A malformed `strength` node
     * only drops the strength half; the mode table still survives.
     */
    fun ancOf(envelopeJson: String?): MelodyAncPolicy? {
        val envelope = envelopeJson
            ?.let { runCatching { JsonParser.parse(it) as? JsonValue.Object }.getOrNull() }
            ?: return null
        val anc = envelope.values["anc"] as? JsonValue.Object ?: return null
        val uiVersion = (anc.values["uiVersion"] as? JsonValue.NumberValue)?.raw?.toIntOrNull()
            ?: return null
        val modesNode = anc.values["modes"] as? JsonValue.Array ?: return null
        val modes = modesNode.values.mapNotNull { item -> modeOf(item) }
        if (modes.isEmpty()) return null
        return MelodyAncPolicy(uiVersion = uiVersion, modes = modes, strength = strengthOf(anc.values["strength"]))
    }

    /**
     * The D-15「降噪效果」descriptor. All-or-nothing: the state, the action and at least one level are
     * required before anything is projected, so a half-written node leaves the host's own ANC state
     * alone.
     */
    private fun strengthOf(value: JsonValue?): MelodyAncStrengthDefinition? {
        val obj = value as? JsonValue.Object ?: return null
        val state = (obj.values["state"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() }
            ?: return null
        val action = (obj.values["action"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() }
            ?: return null
        val levelsNode = obj.values["levels"] as? JsonValue.Array ?: return null
        if (levelsNode.values.isEmpty()) return null
        val levels = levelsNode.values.map { item -> levelOf(item) ?: return null }
        return MelodyAncStrengthDefinition(
            state = state,
            action = action,
            levels = levels,
        )
    }

    private fun levelOf(value: JsonValue): MelodyAncStrengthLevel? {
        val obj = value as? JsonValue.Object ?: return null
        val modeType = (obj.values["modeType"] as? JsonValue.NumberValue)?.raw?.toIntOrNull() ?: return null
        val protocolIndex = (obj.values["protocolIndex"] as? JsonValue.NumberValue)?.raw?.toIntOrNull()
            ?: return null
        val level = (obj.values["level"] as? JsonValue.NumberValue)?.raw?.toIntOrNull() ?: return null
        return MelodyAncStrengthLevel(modeType = modeType, protocolIndex = protocolIndex, level = level)
    }

    private fun modeOf(value: JsonValue): MelodyAncMode? {
        val obj = value as? JsonValue.Object ?: return null
        val modeType = (obj.values["modeType"] as? JsonValue.NumberValue)?.raw?.toIntOrNull() ?: return null
        val protocolIndex = (obj.values["protocolIndex"] as? JsonValue.NumberValue)?.raw?.toIntOrNull()
            ?: return null
        val state = (obj.values["state"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() }
            ?: return null
        val label = (obj.values["label"] as? JsonValue.StringValue)?.value
        return MelodyAncMode(modeType = modeType, protocolIndex = protocolIndex, state = state, label = label)
    }

    /**
     * Reads the per-device panel policy M4 applies on the detail page (HANDOFF_MELODY_M4_PLAN.md §3
     * M4.0). Like [policyOf] this only reads *our* `panel` node, so an older or truncated envelope
     * never throws - it degrades to an inert [MelodyPanelPolicy] carrying a `missingReason`.
     *
     * Degradation is deliberately all-or-nothing: a half-understood policy (some fields valid, one
     * malformed) means "do not touch the host panel at all", which keeps the official UI exactly as
     * it is (spec §4 item 5, fail-open).
     *
     * @param fallbackTitle used when `sectionTitle` is missing or blank - normally the Definition's
     *   `displayName`; the same value the projection builder writes, so a present-but-empty title is
     *   cosmetic rather than a policy failure.
     */
    fun panelOf(envelopeJson: String?, fallbackTitle: String): MelodyPanelPolicy {
        val envelope = envelopeJson
            ?.let { runCatching { JsonParser.parse(it) as? JsonValue.Object }.getOrNull() }
            ?: return MelodyPanelPolicy.missing(fallbackTitle, MelodyPanelPolicy.MISSING_ENVELOPE)
        val panel = envelope.values["panel"] as? JsonValue.Object
            ?: return MelodyPanelPolicy.missing(fallbackTitle, MelodyPanelPolicy.MISSING_NODE)
        val titleNode = panel.values["sectionTitle"]
        if (titleNode != null && titleNode !is JsonValue.StringValue) {
            return MelodyPanelPolicy.missing(fallbackTitle, MelodyPanelPolicy.MISSING_NODE)
        }
        val sectionTitle = (titleNode as? JsonValue.StringValue)?.value
            ?.takeIf(String::isNotBlank)
            ?: fallbackTitle
        // Field-level failures resolve in a fixed order so the reported reason is deterministic.
        val hideSections = panelKeySet(panel, "hideSections")
            ?: return MelodyPanelPolicy.missing(sectionTitle, MelodyPanelPolicy.FIELD_HIDE_SECTIONS)
        val hideKeys = panelKeySet(panel, "hideKeys")
            ?: return MelodyPanelPolicy.missing(sectionTitle, MelodyPanelPolicy.FIELD_HIDE_KEYS)
        val greyKeys = panelKeySet(panel, "greyKeys")
            ?: return MelodyPanelPolicy.missing(sectionTitle, MelodyPanelPolicy.FIELD_GREY_KEYS)
        return MelodyPanelPolicy(
            sectionTitle = sectionTitle,
            hideSections = hideSections,
            hideKeys = hideKeys,
            greyKeys = greyKeys,
            // M4.3c: the self-built「高级功能」group. It is an additive node, so a malformed one only
            // drops the group (nothing is inserted); the hide/grey policy above stays in force.
            group = panelGroupOf(panel.values["group"]),
        )
    }

    /**
     * Reads the M4.3c `panel.group` node: the「高级功能」card the panel inserts after `sound`. It is
     * all-or-nothing (a malformed key/title/kind drops the whole group) and every key must stay inside
     * the `melody_bridge_*` namespace, so a hand-written envelope cannot smuggle an official key in.
     */
    private fun panelGroupOf(value: JsonValue?): MelodyPanelGroup? {
        val obj = value as? JsonValue.Object ?: return null
        val key = (obj.values["key"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() } ?: return null
        if (!key.startsWith(MelodyPanelDefinition.CUSTOM_KEY_PREFIX)) return null
        val title = (obj.values["title"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() } ?: return null
        val rowsNode = obj.values["rows"] as? JsonValue.Array ?: return null
        val rows = rowsNode.values.map { rowOf(it) ?: return null }
        if (rows.isEmpty()) return null
        return MelodyPanelGroup(key = key, title = title, rows = rows)
    }

    private fun rowOf(value: JsonValue): MelodyPanelRow? {
        val obj = value as? JsonValue.Object ?: return null
        val kind = MelodyPanelRowKind.fromWire((obj.values["kind"] as? JsonValue.StringValue)?.value) ?: return null
        val key = (obj.values["key"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() } ?: return null
        if (!key.startsWith(MelodyPanelDefinition.CUSTOM_KEY_PREFIX)) return null
        val title = (obj.values["title"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() } ?: return null
        val state = (obj.values["state"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() }
        val action = (obj.values["action"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() }
        val param = (obj.values["param"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() }
        val valueType = (obj.values["valueType"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() }
        val options = stringList(obj.values["options"])
        val optionLabels = stringList(obj.values["labels"])
        // M4.4: the typed `args` object. A present-but-malformed one means the row could fire a
        // half-built action, so it is greyed out instead of silently losing an argument.
        val argsNode = obj.values["args"]
        val args = panelArgsOf(argsNode)
        val argsMalformed = argsNode != null && args == null
        return MelodyPanelRow(
            kind = kind,
            key = key,
            title = title,
            state = state,
            action = action,
            param = param,
            valueType = valueType,
            args = args ?: emptyMap(),
            options = options,
            optionLabels = optionLabels,
            min = number(obj.values["min"]),
            max = number(obj.values["max"]),
            step = number(obj.values["step"]),
            unit = (obj.values["unit"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() },
            unavailable = ((obj.values["unavailable"] as? JsonValue.BooleanValue)?.value ?: false) || argsMalformed,
        )
    }

    private fun stringList(value: JsonValue?): List<String> {
        val array = value as? JsonValue.Array ?: return emptyList()
        return array.values.mapNotNull { (it as? JsonValue.StringValue)?.value }
    }

    /**
     * Reads the M4.4 typed `args` node (`{ "<name>": { "type": ..., "value": ... } }`). Absent = empty
     * map (a Button with no literal args is legal); present but malformed = `null`, which greys the row.
     */
    private fun panelArgsOf(value: JsonValue?): Map<String, MelodyPanelArg>? {
        if (value == null) return emptyMap()
        val obj = value as? JsonValue.Object ?: return null
        val out = LinkedHashMap<String, MelodyPanelArg>(obj.values.size)
        for ((name, item) in obj.values) {
            val arg = item as? JsonValue.Object ?: return null
            val type = (arg.values["type"] as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() } ?: return null
            val text = (arg.values["value"] as? JsonValue.StringValue)?.value ?: return null
            out[name] = MelodyPanelArg(type = type, value = text)
        }
        return out
    }

    private fun number(value: JsonValue?): Double? = (value as? JsonValue.NumberValue)?.raw?.toDouble()

    /**
     * The keys of one `panel` list field, or `null` when it is present but not a valid list of
     * official keys. An absent field is an empty list (nothing to hide/grey), not a failure; a
     * blank entry or a `melody_bridge_*` entry is a failure even though the Definition validator
     * already rejects them, so a hand-written envelope cannot slip past the runtime.
     */
    private fun panelKeySet(panel: JsonValue.Object, name: String): Set<String>? {
        val value = panel.values[name] ?: return emptySet()
        val array = value as? JsonValue.Array ?: return null
        val keys = LinkedHashSet<String>(array.values.size)
        for (item in array.values) {
            val key = (item as? JsonValue.StringValue)?.value ?: return null
            if (key.isBlank()) return null
            if (key.startsWith(MelodyPanelDefinition.CUSTOM_KEY_PREFIX)) return null
            keys += key
        }
        return keys
    }

    /**
     * The table the hook should return for [path], or `null` when the surface is not synthesised
     * (`whitelist_content` without a readable official blob, wear rows, unknown paths).
     */
    fun merge(
        path: MelodyQueryPath,
        official: MelodyInjectionTable?,
        identity: MelodyWhitelistIdentity,
    ): MelodyInjectionTable? = when (path) {
        MelodyQueryPath.FIND_WHITELIST -> mergeRow(
            official = official,
            defaultColumns = FIND_WHITELIST_COLUMNS,
            row = linkedMapOf(
                "name" to identity.name,
                // The host's own `find_whitelist` row writes `WhitelistConfigDTO.getId()` here.
                "product_id" to identity.hexId,
                "content" to identity.whitelistJson,
            ),
            alreadyPresent = { rows, columns -> containsProductId(rows, columns, identity) },
        )

        MelodyQueryPath.EARS_WHITELIST -> mergeRow(
            official = official,
            defaultColumns = EARS_WHITELIST_COLUMNS,
            row = linkedMapOf(
                "name" to identity.name,
                // The outside snapshot uses the decimal form.
                "product_id" to identity.decimalId,
                "support_wear_check" to 0L,
                "type" to identity.type,
            ),
            alreadyPresent = { rows, columns -> containsProductId(rows, columns, identity) },
        )

        MelodyQueryPath.ALL_WHITELIST -> mergeRow(
            official = official,
            defaultColumns = ALL_WHITELIST_COLUMNS,
            row = linkedMapOf("content" to identity.whitelistJson),
            alreadyPresent = { rows, columns -> rows.any { contentIdOf(it, columns) == identity.hexId } },
        )

        MelodyQueryPath.WHITELIST_CONTENT -> mergeWhitelistContent(official, identity)

        MelodyQueryPath.EARPHONE_BOTH_IN_EAR,
        MelodyQueryPath.ACTIVE_EARPHONE_BOTH_IN_EAR,
        -> null
    }

    /** Appends `{address, both_in_ear}` for a managed MAC that has a live wear state. */
    fun mergeWear(official: MelodyInjectionTable?, mac: String, bothInEar: Boolean): MelodyInjectionTable {
        val columns = official?.columns?.takeIf { it.isNotEmpty() } ?: WEAR_COLUMNS
        val rows = official?.rows.orEmpty()
        val normalized = MelodyMac.normalize(mac)
        if (rows.any { addressOf(it, columns) == normalized }) {
            return MelodyInjectionTable(columns, rows)
        }
        val values = columns.map { column ->
            when (column) {
                "address" -> normalized
                "both_in_ear" -> if (bothInEar) 1L else 0L
                else -> null
            }
        }
        return MelodyInjectionTable(columns, rows + listOf(values))
    }

    /**
     * Appends the row of every managed device the host does not already carry.
     *
     * The whole-list surfaces (`ears_whitelist`, `all_whitelist`) carry no device parameter, so the
     * answer must contain *all* of our devices - resolving a single "targeted" device here is the bug
     * that leaves a headset out of the host's device list. `null` means "nothing changed", which lets
     * the caller hand the official cursor back untouched.
     */
    fun mergeAll(
        path: MelodyQueryPath,
        official: MelodyInjectionTable?,
        identities: List<MelodyWhitelistIdentity>,
    ): MelodyInjectionTable? {
        var table = official
        var appended = 0
        for (identity in identities) {
            val before = table?.rows?.size ?: 0
            val merged = merge(path, table, identity) ?: continue
            val after = merged.rows.size
            if (after > before) {
                appended += after - before
                table = merged
            }
        }
        return if (appended > 0) table else null
    }

    /** gzip(JSON) of the `whitelist_content` DO with our entry appended to `whiteList`, or `null`. */
    fun mergeWhitelistContent(
        official: MelodyInjectionTable?,
        identity: MelodyWhitelistIdentity,
    ): MelodyInjectionTable? {
        val table = official ?: return null
        val contentIndex = table.columns.indexOf("content")
        if (contentIndex < 0) return null
        val blob = table.rows.firstOrNull()?.getOrNull(contentIndex) as? ByteArray ?: return null
        if (blob.size > MAX_MERGE_BYTES) return null
        val merged = mergeWhitelistContentBytes(blob, identity) ?: return null
        if (merged.size > MAX_MERGE_BYTES) return null
        val rows = table.rows.mapIndexed { index, row ->
            if (index != 0) row else row.toMutableList().also { it[contentIndex] = merged }
        }
        return MelodyInjectionTable(table.columns, rows)
    }

    /**
     * Decompresses the official DO, appends our `WhitelistConfigDTO` to `whiteList` (unless already
     * present) and compresses the result again. Every other DO field is preserved verbatim.
     */
    internal fun mergeWhitelistContentBytes(
        blob: ByteArray,
        identity: MelodyWhitelistIdentity,
    ): ByteArray? {
        val json = gunzip(blob) ?: return null
        if (json.length > MAX_MERGE_BYTES) return null
        val root = runCatching { JsonParser.parse(json) as? JsonValue.Object }.getOrNull() ?: return null
        val whiteList = root.values["whiteList"] as? JsonValue.Array ?: return null
        if (whiteList.values.any { idOf(it) == identity.hexId }) return blob
        val entry = runCatching { JsonParser.parse(identity.whitelistJson) }.getOrNull() ?: return null
        val fields = LinkedHashMap(root.values)
        fields["whiteList"] = JsonValue.Array(whiteList.values + entry)
        return gzip(JsonWriter.write(JsonValue.Object(fields)))
    }

    // --- internals ------------------------------------------------------------------------------

    private inline fun mergeRow(
        official: MelodyInjectionTable?,
        defaultColumns: List<String>,
        row: Map<String, Any?>,
        alreadyPresent: (rows: List<List<Any?>>, columns: List<String>) -> Boolean,
    ): MelodyInjectionTable {
        val columns = official?.columns?.takeIf { it.isNotEmpty() } ?: defaultColumns
        val rows = official?.rows.orEmpty()
        if (alreadyPresent(rows, columns)) return MelodyInjectionTable(columns, rows)
        return MelodyInjectionTable(columns, rows + listOf(columns.map { row[it] }))
    }

    private fun containsProductId(
        rows: List<List<Any?>>,
        columns: List<String>,
        identity: MelodyWhitelistIdentity,
    ): Boolean = rows.any { row ->
        val id = valueOf(row, columns, "product_id") as? String
        id != null && (id == identity.hexId || id == identity.decimalId)
    }

    private fun contentIdOf(row: List<Any?>, columns: List<String>): String? =
        (valueOf(row, columns, "content") as? String)?.let { runCatching { JsonParser.parse(it) }.getOrNull() }
            ?.let(::idOf)

    private fun addressOf(row: List<Any?>, columns: List<String>): String? {
        val value = valueOf(row, columns, "address") as? String ?: return null
        return MelodyMac.normalize(value)
    }

    private fun valueOf(row: List<Any?>, columns: List<String>, column: String): Any? {
        val index = columns.indexOf(column)
        return if (index < 0) null else row.getOrNull(index)
    }

    private fun idOf(value: JsonValue): String? =
        ((value as? JsonValue.Object)?.values?.get("id") as? JsonValue.StringValue)?.value?.uppercase()

    internal fun gzip(text: String): ByteArray? = runCatching {
        ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
    }.getOrNull()

    internal fun gunzip(bytes: ByteArray): String? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}

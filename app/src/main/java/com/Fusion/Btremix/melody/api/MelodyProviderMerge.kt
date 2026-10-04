package com.Fusion.Btremix.melody.api

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

package com.Fusion.Btremix.definition.api

/**
 * The `melody` section of a Definition (schema version [DefinitionSchema.VERSION_MELODY]).
 *
 * It answers two questions only: who this device is inside ColorOS Melody, and which official rows
 * the Melody panel should hide or grey out. It deliberately never defines controls - custom rows come
 * from the definition's existing `states` / `actions` / `ui` sections.
 *
 * The section is optional. A definition without it is never advertised to Melody and behaves exactly
 * as it did before schema version 4 existed.
 *
 * The model lives in `definition.api` (not `melody.*`) because it is part of the Definition schema;
 * the dependency direction forbids `definition` from importing `melody`.
 */
data class MelodySectionDefinition(
    val support: MelodySupportDefinition,
    /** Panel policy is carried by M3 but only applied by M4; see [MelodyPanelDefinition]. */
    val panel: MelodyPanelDefinition = MelodyPanelDefinition(),
    /** Native ANC mode projection (M4.3b, decision D-12); see [MelodyAncDefinition]. */
    val anc: MelodyAncDefinition = MelodyAncDefinition(),
)

/**
 * One row of the host's native noise-reduction mode table (M4.3b, decisions D-11/D-12).
 *
 * The host does not render by [state]; it renders by [modeType] (a fixed vocabulary, see
 * `docs/melody-capability-map.md` §7.1.3) and highlights the row whose [protocolIndex] equals the
 * value the Definition projects for the current ANC mode. [state] is the Definition's own enum value
 * (e.g. `anc` / `off` / `ambient` / `wind`) and is what ties a live state to a host row; [label] is
 * the text the mode hook writes into the host's `ModeItem.name` and defaults to the Definition's
 * `states.<anc>.enumValues[state]` display name.
 */
data class MelodyAncMode(
    val modeType: Int,
    val protocolIndex: Int,
    val state: String,
    val label: String? = null,
)

/**
 * The `melody.anc` node: the Definition's ANC mode table as the host should render it (M4.3b D-12).
 *
 * It is the single source of truth for the `function.noiseReductionMode` switch (including the
 * nested `childrenMode` list behind the host's own「降噪效果」row), the
 * `function.noiseReductionUIVersion` switch and the `EarphoneDTO.getNoiseReductionModeIndex()`
 * projection. [uiVersion] only changes the host's render order (1 -> `[4,3,5,10,1,2,6]`, 2 ->
 * `[4,3,5,10,2,6,1]`); the vocabulary has no `wind` slot, so `wind`-shaped modes go into the
 * Adaptive (10) slot and are relabelled by the mode hook.
 *
 * [modes] may be left empty, in which case [com.Fusion.Btremix.melody.projection.MelodyCapabilityMap]
 * derives the table from the Definition's ANC enum state by name convention (`off`/`anc`/`ambient`/
 * `wind`). A package that needs a different table (a headset with a "similar to wind" mode) declares
 * the whole table here instead of changing module code.
 */
data class MelodyAncDefinition(
    val uiVersion: Int = DEFAULT_UI_VERSION,
    val modes: List<MelodyAncMode> = emptyList(),
    /**
     * The ANC strength mapping behind the host's own「降噪效果」row (M4.3b D-15).
     * `null` means the Definition has no strength state and the native `noise` group renders its mode
     * cells only.
     */
    val strength: MelodyAncStrengthDefinition? = null,
) {
    companion object {
        const val DEFAULT_UI_VERSION: Int = 1

        /** Host render orders M4 knows about; anything else is a definition error. */
        val UI_VERSIONS: IntRange = 1..2
    }
}

/**
 * The Definition side of the host's own「降噪效果」control (M4.3b D-15).
 *
 * A non-empty [levels] is emitted as the `childrenMode` list of the injected mode table's
 * `modeType == 5` (Noise cancelling) entry. The host then renders its native `NoiseReductionSelectItem`
 * row titled「降噪效果」whose popup lists one entry per [MelodyAncStrengthLevel] (host labels:
 * Low / Moderate / High / Auto). [state] is the Definition state read for the live value and [action]
 * the action that would write it back (M5).
 */
data class MelodyAncStrengthDefinition(
    val state: String,
    val action: String,
    /** The native positions, in the order the host popup lists them. */
    val levels: List<MelodyAncStrengthLevel>,
) {
    companion object {
        /** Host `modeType` of the Noise-cancelling entry that owns the「降噪效果」children. */
        const val HOST_PARENT_MODE_TYPE: Int = 5
    }
}

/**
 * One position of the host's native「降噪效果」list.
 *
 * [modeType] is the host's child-mode vocabulary - `3` Low, `8` Moderate, `4` High, `7` Auto; the host
 * renders no label for anything else, so such an entry would be invisible. [protocolIndex] is the value
 * the host writes when the user picks the position (`earphone/b;->v0`) and the value the module projects
 * for the current state, so it must be unique across the whole mode table (parents + children).
 * [level] is the Definition's own strength value the position stands for (Sony's `ancLevel`).
 */
data class MelodyAncStrengthLevel(
    val modeType: Int,
    val protocolIndex: Int,
    val level: Int,
) {
    companion object {
        /** Host child `modeType` values that `Ba.r.b` renders a title for (Low/High/Auto/Moderate). */
        val HOST_MODE_TYPES: Set<Int> = setOf(3, 4, 7, 8)
    }
}

/**
 * Fields describing the device itself as Melody should see it.
 *
 * [productId] is stored in its normalized decimal form (see [MelodyProductId]) because that is the
 * form Melody uses for `ears_whitelist.product_id` and for `find_whitelist` selection arguments;
 * the uppercase hexadecimal form used by `WhitelistConfigDTO.id` is derived from it when synthesizing.
 */
data class MelodySupportDefinition(
    val name: String,
    /** Only [MODE_BRIDGE] is accepted; `mirror` / `virtual` are reserved for later milestones. */
    val mode: String = MODE_BRIDGE,
    val brand: String? = null,
    /** Normalized decimal form. `null` means "take the value from the host instance, else the template". */
    val productId: String? = null,
    val productType: Int = DEFAULT_PRODUCT_TYPE,
    val uuid: String? = null,
    /** `false` keeps Melody from opening its own SPP control channel for this device. */
    val supportSpp: Boolean = false,
    val suppressMelodyTransport: Boolean = true,
    /** APK asset path of the whitelist template; `.dcpkg`-provided templates are not supported in M3. */
    val templateWhitelist: String? = null,
    /**
     * Optional host version range (M5.4, D-21/D-26), see [MelodyHostVersions]. `null` means no
     * restriction, which is the shipped 1.4.0 behaviour; a declared range that the host on device
     * does not match makes the injection fail open (`melody.host.version_unsupported`).
     */
    val hostVersions: String? = null,
) {
    companion object {
        const val MODE_BRIDGE: String = "bridge"
        const val DEFAULT_PRODUCT_TYPE: Int = 1
        const val DEFAULT_TEMPLATE_WHITELIST: String = "assets/melody/whitelist-template.json"
    }
}

/**
 * Panel policy for the Melody detail page. M3 only transports these fields inside the projection
 * envelope so M4 can consume them without another contract change; nothing reads them yet.
 */
data class MelodyPanelDefinition(
    val sectionTitle: String? = null,
    val hideSections: List<String> = emptyList(),
    val hideKeys: List<String> = emptyList(),
    val greyKeys: List<String> = emptyList(),
) {
    companion object {
        /**
         * Custom rows BtRemix inserts are namespaced with this prefix. Hide/grey rules are validated
         * against official rows only, so a rule may never target this namespace (spec D5).
         */
        const val CUSTOM_KEY_PREFIX: String = "melody_bridge_"
    }
}

/**
 * Product-id conversions between the two equivalent forms ColorOS Melody uses.
 *
 * The M3.-1 whitelist export shows both forms side by side: `WhitelistConfigDTO.id` (and
 * `diagnosis_list.product_id`) is the uppercase, zero-padded hexadecimal form such as `06F010`,
 * while `ears_whitelist.product_id` and the `find_whitelist` selection argument carry the decimal
 * form of the same number (`454672`). A definition always stores the decimal form:
 *
 * - a bare token is decimal (`"454672"` -> `"454672"`);
 * - a `0x`-prefixed token is hexadecimal (`"0x06F010"` -> `"454672"`);
 * - a bare token containing hex letters can only be hexadecimal (`"06F010"` -> `"454672"`).
 *
 * A bare all-digit token is always read as decimal, even with leading zeros, so a hex form copied
 * from the export (`content.id`) must keep its `0x` prefix.
 */
object MelodyProductId {
    const val HEX_WIDTH: Int = 6
    private const val MAX_VALUE: Long = 0xFFFF_FFFFL

    /** Returns the decimal form of [raw], or `null` when it is not a valid product id. */
    fun normalizeOrNull(raw: String): String? {
        val token = raw.trim()
        if (token.isEmpty()) return null
        val value = when {
            token.startsWith("0x", ignoreCase = true) -> parseHex(token.substring(2))
            token.all { it in '0'..'9' } -> token.toLongOrNull()
            else -> parseHex(token)
        } ?: return null
        if (value > MAX_VALUE) return null
        return value.toString()
    }

    /** Uppercase hexadecimal form used by `WhitelistConfigDTO.id`, zero padded to [width]. */
    fun toHexId(value: Long, width: Int = HEX_WIDTH): String {
        require(value in 0..MAX_VALUE) { "product id $value does not fit UInt32" }
        return java.lang.Long.toHexString(value).uppercase().padStart(width, '0')
    }

    private fun parseHex(digits: String): Long? {
        if (digits.isEmpty() || digits.length > 8) return null
        if (digits.any { it.digitToIntOrNull(16) == null }) return null
        return digits.toLongOrNull(16)
    }
}

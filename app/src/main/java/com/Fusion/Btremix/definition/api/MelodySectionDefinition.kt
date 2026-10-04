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
)

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

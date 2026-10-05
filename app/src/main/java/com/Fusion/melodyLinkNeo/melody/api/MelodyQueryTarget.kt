package com.fusion.melodyLinkNeo.melody.api

import com.fusion.melodyLinkNeo.definition.api.MelodyProductId

/**
 * One `MelodyAliveProvider` URI path the M3.3 injection knows how to answer
 * (`COLOROS_MELODY_ANALYSIS.md` §K2, `MELODY_BRIDGE_SPEC` §5.1).
 */
enum class MelodyQueryPath(val path: String) {
    FIND_WHITELIST("find_whitelist"),
    EARS_WHITELIST("ears_whitelist"),
    ALL_WHITELIST("all_whitelist"),
    WHITELIST_CONTENT("whitelist_content"),
    EARPHONE_BOTH_IN_EAR("earphone_both_in_ear"),
    ACTIVE_EARPHONE_BOTH_IN_EAR("active_earphone_both_in_ear");

    /** The two surfaces that answer with a live wear flag rather than a whitelist entry. */
    val isWear: Boolean
        get() = this == EARPHONE_BOTH_IN_EAR || this == ACTIVE_EARPHONE_BOTH_IN_EAR

    /**
     * The surfaces that answer with the *whole* whitelist and carry no device parameter, so every
     * managed device belongs in the merged answer (M3.3 plan §4, `MELODY_BRIDGE_SPEC` §5.1).
     */
    val isWholeList: Boolean
        get() = this == EARS_WHITELIST || this == ALL_WHITELIST || this == WHITELIST_CONTENT

    companion object {
        /** Accepts `/find_whitelist`, `find_whitelist` and a trailing slash. */
        fun of(path: String?): MelodyQueryPath? {
            val normalized = path?.trim()?.trim('/')?.takeIf(String::isNotEmpty) ?: return null
            return entries.firstOrNull { it.path == normalized }
        }
    }
}

/**
 * The parameters of a provider query the injection cares about, merged from the URI query string and
 * the `selection`/`selectionArgs` pair (M3.3 plan §4 "命中判定").
 *
 * The host asks `find_whitelist` either by `macAddress` or by `productId` + `deviceName`
 * (`COLOROS_MELODY_ANALYSIS.md` §B), and the wear rows carry `address`. The same key can show up in
 * either channel depending on the caller (SystemUI vs. a `content query` shell), so both are parsed
 * and the URI query string wins when they disagree. This is deliberately a pure function with no
 * `android.net.Uri` dependency so the mapping is covered by JVM tests.
 */
data class MelodyQueryTarget(
    val path: MelodyQueryPath,
    /** Normalised MAC address, when the caller asked by address. */
    val mac: String?,
    /** Product id normalised to decimal (`MelodyProductId`), or the raw token when not normalisable. */
    val productId: String?,
    val deviceName: String?,
) {

    /**
     * Whether the device identified by [candidateMac] / [candidateDecimalId] / [candidateHexId] /
     * [candidateName] is the subject of this query. MAC wins over the `productId` + `deviceName` pair;
     * the name only narrows the match when it was part of the query (M3.3 plan §4 "命中判定").
     */
    fun matches(
        candidateMac: String?,
        candidateName: String?,
        candidateDecimalId: String?,
        candidateHexId: String?,
    ): Boolean {
        if (mac != null) return candidateMac != null && mac == MelodyMac.normalize(candidateMac)
        val wanted = productId ?: return false
        val wantedDecimal = MelodyProductId.normalizeOrNull(wanted)
        val wantedHex = wantedDecimal?.toLongOrNull()?.let { MelodyProductId.toHexId(it) } ?: wanted.uppercase()
        val idMatches = (wantedDecimal != null && wantedDecimal == candidateDecimalId) ||
            wantedHex == candidateHexId?.uppercase()
        if (!idMatches) return false
        val wantedName = deviceName ?: return true
        // The host asks with the Bluetooth device name ("WF-1000XM3"), which is usually shorter than the
        // whitelist display name ("Sony WF-1000XM3"), so containment is the useful test here - matching
        // the host's own fuzzy lookup. The product id above is still the primary key.
        val query = normalizeName(wantedName)
        val candidate = normalizeName(candidateName)
        return query.isNotEmpty() && candidate.isNotEmpty() &&
            (query == candidate || candidate.contains(query) || query.contains(candidate))
    }

    companion object {

        /**
         * @param path the provider URI path (with or without the leading `/`).
         * @param queryParameters the URI query string decoupled from `android.net.Uri`.
         * @param selection the provider `selection` argument, or `null`.
         * @param selectionArgs the provider `selectionArgs`, used to substitute `?` placeholders.
         */
        fun parse(
            path: String?,
            queryParameters: Map<String, String?> = emptyMap(),
            selection: String? = null,
            selectionArgs: Array<String>? = null,
        ): MelodyQueryTarget? {
            val resolved = MelodyQueryPath.of(path) ?: return null
            val merged = LinkedHashMap<String, String>()
            queryParameters.forEach { (key, value) ->
                val token = unquote(value) ?: return@forEach
                merged.putIfAbsent(key.trim().lowercase(), token)
            }
            selectionPairs(selection, selectionArgs).forEach { (key, value) ->
                merged.putIfAbsent(key, value)
            }
            val mac = (merged["macaddress"] ?: merged["address"])?.let(MelodyMac::normalize)
            val productId = merged["productid"]?.let { MelodyProductId.normalizeOrNull(it) ?: it }
            return MelodyQueryTarget(
                path = resolved,
                mac = mac?.takeIf(String::isNotEmpty),
                productId = productId?.takeIf(String::isNotEmpty),
                deviceName = merged["devicename"]?.takeIf(String::isNotEmpty),
            )
        }

        /** Substitutes `?` placeholders (outside quotes), then reads `key = value` pairs. */
        internal fun selectionPairs(selection: String?, args: Array<String>?): Map<String, String> {
            val text = selection?.trim().orEmpty()
            if (text.isEmpty()) return emptyMap()
            val substituted = substituteArgs(text, args)
            val result = LinkedHashMap<String, String>()
            SELECTION_PAIR.findAll(substituted).forEach { match ->
                val key = match.groupValues[1].trim().lowercase()
                val value = unquote(
                    match.groupValues[2].ifEmpty { match.groupValues[3] }
                        .ifEmpty { match.groupValues[4] },
                ) ?: return@forEach
                result.putIfAbsent(key, value)
            }
            return result
        }

        private fun substituteArgs(selection: String, args: Array<String>?): String {
            if (args.isNullOrEmpty() || '?' !in selection) return selection
            val out = StringBuilder(selection.length + 16)
            var argIndex = 0
            var quote: Char? = null
            for (ch in selection) {
                when {
                    quote != null -> {
                        out.append(ch)
                        if (ch == quote) quote = null
                    }
                    ch == '\'' || ch == '"' -> {
                        quote = ch
                        out.append(ch)
                    }
                    ch == '?' && argIndex < args.size -> out.append(args[argIndex++])
                    else -> out.append(ch)
                }
            }
            return out.toString()
        }

        /** Strips one layer of surrounding single/double quotes and trims; blank becomes `null`. */
        internal fun unquote(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.length >= 2) {
                val first = trimmed.first()
                val last = trimmed.last()
                if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                    return trimmed.substring(1, trimmed.length - 1).trim().takeIf(String::isNotEmpty)
                }
            }
            return trimmed.takeIf(String::isNotEmpty)
        }

        internal fun normalizeName(value: String?): String =
            value?.trim()?.lowercase()?.replace(WHITESPACE, " ")?.trim().orEmpty()

        private val WHITESPACE = Regex("\\s+")

        private val SELECTION_PAIR =
            Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:'([^']*)'|"([^"]*)"|([^\s)]+))""")
    }
}

package com.Fusion.Btremix.definition.api

/**
 * Host version range declared by `melody.support.hostVersions` (M5.4, decisions D-21/D-25/D-26).
 *
 * The spec is a whitespace-separated list of predicates that must all hold, for example
 * `">=17.6.3 <18"`; a bare token is shorthand for `>=` (`"17.6.3"` == `">=17.6.3"`). Comparison is
 * segment-wise over the leading digit run of each dot-separated segment, missing segments read as 0:
 * `17.6.3` matches `>=17.6.3`, `17.6.10` is newer than `17.6.3` (numeric, not lexical).
 *
 * This lives in `definition.api` next to [MelodyProductId] because it is part of the Definition
 * schema: the validator has to check it, and the dependency direction forbids `definition` from
 * importing `melody`. The hook side may import this class.
 *
 * A spec that cannot be parsed yields `null` from [parse] - runtime callers then treat the device as
 * ungated (fail-open, the 1.4.0 default). The validator reports the same input as a pack error, so a
 * definition that reaches the runtime malformed is a hand-edited file rather than a shipped package.
 */
class MelodyHostVersions private constructor(
    /** The trimmed spec, echoed into `melody.host.version_unsupported range=`. */
    val spec: String,
    private val predicates: List<Predicate>,
) {

    /** True when [version] satisfies every predicate; an unreadable [version] never matches. */
    fun matches(version: String?): Boolean {
        val parsed = parseVersion(version) ?: return false
        return predicates.all { it.matches(parsed) }
    }

    override fun toString(): String = spec

    private class Predicate(private val operator: Operator, private val bound: List<Int>) {
        fun matches(version: List<Int>): Boolean {
            val cmp = compare(version, bound)
            return when (operator) {
                Operator.AT_LEAST -> cmp >= 0
                Operator.GREATER -> cmp > 0
                Operator.AT_MOST -> cmp <= 0
                Operator.LESS -> cmp < 0
            }
        }
    }

    private enum class Operator(val token: String) {
        AT_LEAST(">="),
        GREATER(">"),
        AT_MOST("<="),
        LESS("<"),
    }

    companion object {

        /** Definition field name, kept next to the parser so the validator and docs cannot drift. */
        const val FIELD: String = "melody.support.hostVersions"

        /** Parses [spec]; `null` when it is blank or any token is not a valid predicate. */
        fun parse(spec: String?): MelodyHostVersions? {
            val trimmed = spec?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val predicates = ArrayList<Predicate>()
            for (token in trimmed.split(Regex("\\s+"))) {
                predicates += parsePredicate(token) ?: return null
            }
            return MelodyHostVersions(trimmed, predicates)
        }

        /**
         * Whether [version] has a comparable shape at all. The gate uses this to tell
         * `melody.host.version_unsupported reason=unreadable` from `reason=out_of_range`.
         */
        fun isReadable(version: String?): Boolean = parseVersion(version) != null

        private fun parsePredicate(token: String): Predicate? {
            val operator = when {
                token.startsWith(">=") -> Operator.AT_LEAST
                token.startsWith("<=") -> Operator.AT_MOST
                token.startsWith(">") -> Operator.GREATER
                token.startsWith("<") -> Operator.LESS
                else -> null
            }
            val digits = if (operator == null) token else token.substring(operator.token.length)
            val bound = parseVersion(digits) ?: return null
            return Predicate(operator ?: Operator.AT_LEAST, bound)
        }

        /** `17.6.3` -> `[17, 6, 3]`; the leading digit run of each segment, `null` when there is none. */
        private fun parseVersion(version: String?): List<Int>? {
            val trimmed = version?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val segments = trimmed.split('.')
            val parsed = ArrayList<Int>(segments.size)
            for (segment in segments) {
                val digits = segment.takeWhile { it in '0'..'9' }
                if (digits.isEmpty()) return null
                parsed += digits.toIntOrNull() ?: return null
            }
            return parsed
        }

        private fun compare(left: List<Int>, right: List<Int>): Int {
            val size = maxOf(left.size, right.size)
            for (index in 0 until size) {
                val result = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
                if (result != 0) return result
            }
            return 0
        }
    }
}

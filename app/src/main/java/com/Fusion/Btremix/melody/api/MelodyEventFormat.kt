package com.Fusion.Btremix.melody.api

/**
 * The `evt=<name> k=v` line format shared by every Melody bridge component (MELODY_BRIDGE_SPEC §12).
 *
 * M1 introduced the shape inside [com.Fusion.Btremix.melody.hook.MelodyLog]; M2b needs the exact same
 * shape from both sides of the process boundary (the injected client inside `com.oplus.melody` and the
 * service inside BtRemix), so the formatting rules live here as plain Kotlin with no Android imports.
 * That keeps the quoting/truncation behaviour pinned down by ordinary JVM tests instead of by logcat
 * archaeology, and both sides stay machine-parseable by the same `adb logcat -s BtRemixMelody`.
 */
object MelodyEventFormat {

    const val PREFIX: String = "evt="
    const val MAX_VALUE_CHARS: Int = 240
    const val MAX_DETAIL_CHARS: Int = 900

    /** `evt=<name> k=v k2="v with space"`; see [formatValue] for the quoting rules. */
    fun line(name: String, pairs: List<Pair<String, Any?>>): String = buildString {
        append(PREFIX).append(name)
        for ((key, value) in pairs) {
            append(' ').append(key).append('=').append(formatValue(value))
        }
    }

    /** `evt=<name> <flattened one-line detail>`, for payloads that are too long for key/value pairs. */
    fun detailLine(name: String, message: String, maxChars: Int = MAX_DETAIL_CHARS): String =
        PREFIX + name + " " + sanitize(message, maxChars)

    /** `evt=<name>` with an optional `error=` annotation. */
    fun warnLine(name: String, throwable: Throwable?): String =
        PREFIX + name + if (throwable == null) "" else " error=" + describe(throwable)

    /** `k=v` when the value is token-shaped, `k="v"` when it contains whitespace or `=`. */
    fun formatValue(value: Any?, maxChars: Int = MAX_VALUE_CHARS): String {
        if (value == null) return "null"
        val text = sanitize(value.toString(), maxChars)
        val needsQuotes = text.isEmpty() || text.any { it.isWhitespace() } || text.contains('=')
        return if (needsQuotes) "\"" + text.replace('"', '\'') + "\"" else text
    }

    /** Collapses line breaks/tabs into single spaces and bounds the length with an ellipsis. */
    fun sanitize(text: String, maxChars: Int): String {
        val flattened = buildString(text.length) {
            var lastWasSpace = false
            for (ch in text) {
                val replacement = if (ch == '\n' || ch == '\r' || ch == '\t') ' ' else ch
                if (replacement == ' ') {
                    if (!lastWasSpace) append(' ')
                    lastWasSpace = true
                } else {
                    append(replacement)
                    lastWasSpace = false
                }
            }
        }.trim()
        return if (flattened.length <= maxChars) flattened else flattened.take(maxChars - 1) + "…"
    }

    /** `SimpleName: message` with the message sanitised, or just the class name when it is blank. */
    fun describe(throwable: Throwable): String {
        val message = throwable.message
        return if (message.isNullOrBlank()) {
            throwable.javaClass.simpleName
        } else {
            throwable.javaClass.simpleName + ": " + sanitize(message, MAX_VALUE_CHARS)
        }
    }
}

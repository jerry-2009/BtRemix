package com.fusion.melodyLinkNeo.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `evt=` line shape the whole bridge logs with (MELODY_BRIDGE_SPEC §12 M2b).
 *
 * M1's acceptance was read from logcat, so the format is effectively an interface: a change here would
 * silently break every capture recipe in the handoff docs. These cases freeze the quoting, flattening
 * and truncation rules that both the injected client and the BtRemix service share.
 */
class MelodyEventFormatTest {

    @Test
    fun line_quotesValuesWithWhitespaceOrEquals() {
        val line = MelodyEventFormat.line(
            "melody.bridge.snapshot",
            listOf("mac" to "AA:BB:CC:DD:EE:FF", "lifecycle" to "Ready", "note" to "two words"),
        )

        assertEquals("evt=melody.bridge.snapshot mac=AA:BB:CC:DD:EE:FF lifecycle=Ready note=\"two words\"", line)
    }

    @Test
    fun line_rendersNullAndEmptyValues() {
        assertEquals("evt=x a=null b=\"\"", MelodyEventFormat.line("x", listOf("a" to null, "b" to "")))
    }

    @Test
    fun line_replacesInnerQuotesWhenQuoting() {
        assertEquals("evt=x a=\"say 'hi'\"", MelodyEventFormat.line("x", listOf("a" to "say \"hi\"")))
    }

    @Test
    fun sanitize_flattensLineBreaksAndCollapsesSpaces() {
        assertEquals("a b c", MelodyEventFormat.sanitize("a\nb\tc", 64))
        assertEquals("a b", MelodyEventFormat.sanitize("  a   b  ", 64))
    }

    @Test
    fun sanitize_truncatesWithEllipsis() {
        assertEquals("abcd…", MelodyEventFormat.sanitize("abcdef", 5))
    }

    @Test
    fun detailLine_prefixesNameAndFlattensPrettyPrintedJson() {
        val line = MelodyEventFormat.detailLine("melody.provider.cursor", "{\n  \"a\": 1\n}")
        assertEquals("evt=melody.provider.cursor { \"a\": 1 }", line)
    }

    @Test
    fun warnLine_includesDescribedThrowable() {
        val line = MelodyEventFormat.warnLine("melody.bridge.bind_failed", IllegalStateException("boom\nnow"))
        assertEquals("evt=melody.bridge.bind_failed error=IllegalStateException: boom now", line)
    }

    @Test
    fun warnLine_withoutThrowableHasNoErrorField() {
        assertEquals("evt=melody.bridge.bind_refused", MelodyEventFormat.warnLine("melody.bridge.bind_refused", null))
    }

    @Test
    fun describe_handlesBlankMessage() {
        assertTrue(MelodyEventFormat.describe(RuntimeException()).startsWith("RuntimeException"))
    }
}

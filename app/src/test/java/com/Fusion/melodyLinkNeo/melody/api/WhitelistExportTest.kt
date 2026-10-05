package com.fusion.melodyLinkNeo.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.-1: the export hook's naming and rendering rules. The host-side `Cursor` walk is not unit
 * testable (Android types), but everything that decides *what* lands in the dump is.
 */
class WhitelistExportTest {

    @Test
    fun targetOf_mapsTheExportedProviderPaths() {
        assertEquals(
            WhitelistExportTarget("all_whitelist", "whitelist-all.json"),
            WhitelistExport.targetOf("/all_whitelist", null),
        )
        assertEquals(
            WhitelistExportTarget("all_whitelist", "whitelist-all.json"),
            WhitelistExport.targetOf("all_whitelist", null),
        )
        assertEquals(
            WhitelistExportTarget("ears_whitelist", "whitelist-ears.json"),
            WhitelistExport.targetOf("/ears_whitelist", null),
        )
        assertEquals(
            WhitelistExportTarget("whitelist_content", "whitelist-content.json"),
            WhitelistExport.targetOf("/whitelist_content", null),
        )
        assertEquals(
            WhitelistExportTarget("diagnosis_list", "whitelist-diagnosis.json"),
            WhitelistExport.targetOf("/diagnosis_list", null),
        )
    }

    @Test
    fun targetOf_leavesUnrelatedPathsToTheM1RowSample() {
        assertNull(WhitelistExport.targetOf("/earphone_both_in_ear", null))
        assertNull(WhitelistExport.targetOf("/active_earphone_both_in_ear", null))
        assertNull(WhitelistExport.targetOf("/earphone_immersive_record_available", null))
        assertNull(WhitelistExport.targetOf(null, null))
        assertNull(WhitelistExport.targetOf("", null))
        assertNull(WhitelistExport.targetOf("  ", null))
    }

    @Test
    fun findWhitelist_isNamedAfterTheQueriedInstance() {
        assertEquals(
            WhitelistExportTarget("find_whitelist", "whitelist-find-14-3F-A6-02-5F-B0.json"),
            WhitelistExport.targetOf("/find_whitelist", "macAddress=14:3F:A6:02:5F:B0"),
        )
        // Lower-case, quoted selection is normalised; ':' is replaced so the dump pulls on Windows.
        assertEquals(
            "whitelist-find-AA-BB-CC-DD-EE-FF.json",
            WhitelistExport.targetOf("/find_whitelist", "macAddress='aa:bb:cc:dd:ee:ff'")!!.fileName,
        )
        assertEquals(
            "whitelist-find-06F010.json",
            WhitelistExport.targetOf("/find_whitelist", "productId='06F010' AND deviceName='X'")!!.fileName,
        )
        assertEquals(
            "whitelist-find-unknown.json",
            WhitelistExport.targetOf("/find_whitelist", null)!!.fileName,
        )
    }

    @Test
    fun macFromSelection_parsesTheWhereClauseForms() {
        assertEquals("14:3F:A6:02:5F:B0", WhitelistExport.macFromSelection("macAddress=14:3F:A6:02:5F:B0"))
        assertEquals("14:3F:A6:02:5F:B0", WhitelistExport.macFromSelection("macAddress='14:3F:A6:02:5F:B0'"))
        assertEquals("14:3F:A6:02:5F:B0", WhitelistExport.macFromSelection("macAddress = \"14:3f:a6:02:5f:b0\""))
        assertNull(WhitelistExport.macFromSelection("productId='06F010'"))
        assertNull(WhitelistExport.macFromSelection(null))
    }

    @Test
    fun sanitizeToken_boundsAndFallsBack() {
        assertEquals("06F010", WhitelistExport.sanitizeToken("06F010"))
        assertEquals("a-b", WhitelistExport.sanitizeToken("a/b"))
        assertEquals("unknown", WhitelistExport.sanitizeToken("///"))
        assertEquals("unknown", WhitelistExport.sanitizeToken(""))
        assertTrue(WhitelistExport.sanitizeToken("x".repeat(120)).length <= 48)
    }

    @Test
    fun renderDocument_keepsValuesVerbatimAndEscapes() {
        val document = WhitelistExport.renderDocument(
            target = WhitelistExportTarget("all_whitelist", "whitelist-all.json"),
            caller = "root",
            selection = "macAddress=\"14:3F\"",
            columns = listOf("name", "content"),
            rows = listOf(
                listOf(
                    WhitelistExportCell.Text("quote\"newline\n"),
                    WhitelistExportCell.Text("{\"id\":\"06F010\"}"),
                ),
                listOf(WhitelistExportCell.Integer(7), WhitelistExportCell.Blob(13503, "H4sIAA")),
            ),
            hostVersion = "17.6.3",
            timestamp = "2026-10-04T14:17:57+0800",
        )

        assertTrue(document.contains("\"path\": \"all_whitelist\""))
        assertTrue(document.contains("\"hostVersion\": \"17.6.3\""))
        assertTrue(document.contains("\"rowCount\": 2"))
        assertTrue(document.contains("quote\\\"newline\\n"))
        assertTrue(document.contains("\"{\\\"id\\\":\\\"06F010\\\"}\""))
        assertTrue(document.contains("{\"_blob_bytes\": 13503, \"base64\": \"H4sIAA\"}"))
    }

    @Test
    fun renderMeta_carriesTheRequiredFieldsSortedByFile() {
        val meta = WhitelistExport.renderMeta(
            entries = listOf(
                summary("whitelist-ears.json", "ears_whitelist", 93),
                summary("whitelist-all.json", "all_whitelist", 94),
            ),
            generatedAt = "2026-10-04T14:18:00+0800",
        )

        assertTrue(meta.indexOf("whitelist-all.json") < meta.indexOf("whitelist-ears.json"))
        listOf("\"caller\"", "\"selection\"", "\"columns\"", "\"rowCount\"", "\"hostVersion\"",
            "\"timestamp\"", "\"sha256\"", "\"byteLength\"").forEach {
            assertTrue("missing $it", meta.contains(it))
        }
    }

    @Test
    fun quote_handlesNullAndControlCharacters() {
        assertEquals("null", WhitelistExport.quote(null))
        assertEquals("\"a\\tb\"", WhitelistExport.quote("a\tb"))
        assertEquals("\"\\u0001\"", WhitelistExport.quote("\u0001"))
    }

    private fun summary(file: String, path: String, rows: Int) = WhitelistExportSummary(
        fileName = file,
        path = path,
        caller = "root",
        selection = null,
        columns = listOf("content"),
        rowCount = rows,
        hostVersion = "17.6.3",
        timestamp = "2026-10-04T14:17:57+0800",
        sha256 = "0".repeat(64),
        byteLength = rows * 10,
    )
}

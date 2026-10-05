package com.fusion.melodyLinkNeo.melody.api

import com.fusion.melodyLinkNeo.definition.api.MelodyAncMode
import com.fusion.melodyLinkNeo.definition.json.JsonParser
import com.fusion.melodyLinkNeo.definition.json.JsonValue
import com.fusion.melodyLinkNeo.definition.json.JsonWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.3 acceptance for the pure merge: every surface the injection rewrites must produce exactly the
 * shape the host itself uses (`COLOROS_MELODY_ANALYSIS.md` §K2, `docs/melody-official-keys.md` §4) and
 * must stay idempotent so a repeated query never grows the host's answer.
 */
class MelodyProviderMergeTest {

    private val envelope = envelopeJson(hexId = "000CE0")
    private val identity = requireNotNull(MelodyProviderMerge.identityOf(envelope))

    @Test
    fun identity_usesHexIdForTheUppercaseFormAndDecimalForTheColumn() {
        assertEquals("Sony WF-1000XM3", identity.name)
        assertEquals("000CE0", identity.hexId)
        assertEquals("3296", identity.decimalId)
        assertEquals("T1", identity.type)
        assertEquals(false, identity.supportSpp)
        assertEquals("14:3F:A6:02:5F:B0", identity.mac)
    }

    @Test
    fun identity_parsesAllDigitHexIdsAsHexNotDecimal() {
        // `060412` is the OPPO O-Free id from the M3.-1 export: hexadecimal, not 60412.
        val parsed = requireNotNull(MelodyProviderMerge.identityOf(envelopeJson(hexId = "060412")))

        assertEquals("060412", parsed.hexId)
        assertEquals("394258", parsed.decimalId)
    }

    @Test
    fun identity_rejectsAMissingOrTruncatedEnvelope() {
        assertNull(MelodyProviderMerge.identityOf(null))
        assertNull(MelodyProviderMerge.identityOf("{ not json"))
        assertNull(MelodyProviderMerge.identityOf("""{"version":1,"mac":"AA"}"""))
    }

    // --- M4.3b: the `anc` node (mode table +「降噪效果」strength) ------------------------------------

    @Test
    fun ancOf_readsTheModeTableAndTheStrengthDescriptor() {
        val policy = requireNotNull(MelodyProviderMerge.ancOf(ancEnvelopeJson))

        assertEquals(2, policy.uiVersion)
        assertEquals(listOf(5, 1), policy.modes.map { it.modeType })
        assertEquals("Noise canceling", policy.labelOf(5))
        assertEquals("Off", policy.labelOf(1))
        assertNull("an undeclared modeType must stay unlabelled", policy.labelOf(10))
        val strength = requireNotNull(policy.strength)
        assertEquals("ancLevel", strength.state)
        assertEquals("anc.setLevel", strength.action)
        assertEquals(listOf(1, 10, 20), strength.levels.map { it.level })
        assertEquals(listOf(3, 8, 4), strength.levels.map { it.modeType })
        assertEquals(listOf(10, 11, 12), strength.levels.map { it.protocolIndex })
        // M5.1 D-18: the redirect carries the parent action/argument names alongside the tables.
        assertEquals("anc.setMode", policy.modeAction)
        assertEquals("mode", policy.modeParam)
        assertEquals("value", policy.strengthParam)
    }

    @Test
    fun ancOf_keepsEveryStrengthLevel() {
        val policy = requireNotNull(
            MelodyProviderMerge.ancOf(
                """{ "anc": { "uiVersion": 1, "modes": [ { "modeType": 5, "protocolIndex": 0, "state": "anc" } ],
                    "strength": { "state": "ancLevel", "action": "anc.setLevel", "levels": [
                        { "modeType": 3, "protocolIndex": 10, "level": 1 },
                        { "modeType": 4, "protocolIndex": 11, "level": 20 } ] } } }""",
            ),
        )

        assertEquals(listOf(3, 4), policy.strength?.levels?.map { it.modeType })
        assertEquals(listOf(1, 20), policy.strength?.levels?.map { it.level })
    }

    // --- M4.3b D-12: host cell order (`ModeItem.id` is a position, not a modeType) -----------------

    @Test
    fun renderOrder_matchesTheHostsUIVersionOrderFilteredByTheInjectedTable() {
        val modes = listOf(
            MelodyAncMode(modeType = 5, protocolIndex = 0, state = "anc"),
            MelodyAncMode(modeType = 1, protocolIndex = 1, state = "off"),
            MelodyAncMode(modeType = 2, protocolIndex = 2, state = "ambient"),
            MelodyAncMode(modeType = 10, protocolIndex = 3, state = "wind"),
        )

        // 17.6.3 `Le9.r.a`: ui=1 [4,3,5,10,1,2,6], ui=2 [4,3,5,10,2,6,1], filtered to the table.
        assertEquals(listOf(5, 10, 1, 2), MelodyAncRenderOrder.of(1, modes))
        assertEquals(listOf(5, 10, 2, 1), MelodyAncRenderOrder.of(2, modes))
    }

    @Test
    fun renderOrder_filtersToTheDeclaredModeTypes() {
        val modes = listOf(
            MelodyAncMode(modeType = 5, protocolIndex = 0, state = "anc"),
            MelodyAncMode(modeType = 1, protocolIndex = 1, state = "off"),
        )

        assertEquals(listOf(5, 1), MelodyAncRenderOrder.of(1, modes))
        assertEquals(listOf(5, 1), MelodyAncRenderOrder.of(2, modes))
    }

    @Test
    fun ancOf_isNullWhenTheNodeIsMissingTruncatedOrHasNoUsableTable() {
        assertNull(MelodyProviderMerge.ancOf(null))
        assertNull(MelodyProviderMerge.ancOf(envelope))
        assertNull(MelodyProviderMerge.ancOf("""{ "anc": { "uiVersion": 1, "modes": [] } }"""))
        assertNull(MelodyProviderMerge.ancOf("""{ "anc": { "uiVersion": 1, "modes": [ { "modeType": 5 } ] } }"""))
        // The table is fine but the strength descriptor is malformed: the table survives, strength is
        // dropped rather than poisoning the whole node.
        val policy = requireNotNull(
            MelodyProviderMerge.ancOf(
                """{ "anc": { "uiVersion": 1, "modes": [ { "modeType": 5, "protocolIndex": 0, "state": "anc" } ],
                    "strength": { "state": "ancLevel", "levels": [ { "modeType": 3, "protocolIndex": 10 } ] } } }""",
            ),
        )
        assertNull(policy.strength)
    }

    @Test
    fun findWhitelist_buildsTheRowWhenTheOfficialAnswerIsNull() {
        val table = requireNotNull(
            MelodyProviderMerge.merge(MelodyQueryPath.FIND_WHITELIST, official = null, identity = identity),
        )

        assertEquals(MelodyProviderMerge.FIND_WHITELIST_COLUMNS, table.columns)
        assertEquals(1, table.rows.size)
        val row = table.rows.single()
        assertEquals("Sony WF-1000XM3", row[0])
        // The host's own find_whitelist row writes `WhitelistConfigDTO.getId()` here (hexadecimal).
        assertEquals("000CE0", row[1])
        val content = requireNotNull(row[2] as? String)
        assertEquals("000CE0", JsonParser.parse(content).asObject()["id"].asString())
    }

    @Test
    fun findWhitelist_appendsAfterTheOfficialRowsAndKeepsTheColumnOrder() {
        val official = MelodyInjectionTable(
            columns = listOf("content", "name", "product_id"),
            rows = listOf(listOf("{\"id\":\"06F010\"}", "OPPO Enco Air4s", "06F010")),
        )

        val table = requireNotNull(
            MelodyProviderMerge.merge(MelodyQueryPath.FIND_WHITELIST, official, identity),
        )

        assertEquals(listOf("content", "name", "product_id"), table.columns)
        assertEquals(2, table.rows.size)
        // Our row is written by column name, so the host's own order is preserved.
        val row = table.rows[1]
        assertEquals("000CE0", JsonParser.parse(row[0] as String).asObject()["id"].asString())
        assertEquals("Sony WF-1000XM3", row[1])
        assertEquals("000CE0", row[2])
    }

    @Test
    fun findWhitelist_isIdempotentWhenTheOfficialAnswerAlreadyCarriesOurId() {
        val official = MelodyInjectionTable(
            columns = MelodyProviderMerge.FIND_WHITELIST_COLUMNS,
            rows = listOf(listOf("Sony WF-1000XM3", "000CE0", "{}")),
        )

        val table = requireNotNull(
            MelodyProviderMerge.merge(MelodyQueryPath.FIND_WHITELIST, official, identity),
        )

        assertEquals(official.rows, table.rows)
    }

    @Test
    fun earsWhitelist_usesTheDecimalProductIdAndTheObservedColumnSet() {
        val table = requireNotNull(
            MelodyProviderMerge.merge(MelodyQueryPath.EARS_WHITELIST, official = null, identity = identity),
        )

        assertEquals(MelodyProviderMerge.EARS_WHITELIST_COLUMNS, table.columns)
        // The M3.-1 export shows the host answers with three columns; `type` only appears if requested.
        assertEquals(listOf("Sony WF-1000XM3", "3296", 0L), table.rows.single())
    }

    @Test
    fun earsWhitelist_fillsTheTypeColumnWhenTheCallerAskedForIt() {
        val official = MelodyInjectionTable(
            columns = listOf("name", "product_id", "support_wear_check", "type"),
            rows = emptyList(),
        )

        val table = requireNotNull(
            MelodyProviderMerge.merge(MelodyQueryPath.EARS_WHITELIST, official, identity),
        )

        assertEquals(listOf("Sony WF-1000XM3", "3296", 0L, "T1"), table.rows.single())
    }

    @Test
    fun mergeAll_appendsEveryManagedDeviceToAWholeListSnapshot() {
        // `ears_whitelist` carries no device parameter: the answer must gain *all* our devices, which is
        // what makes the headset show up in the host's device list at all.
        val official = MelodyInjectionTable(
            columns = MelodyProviderMerge.EARS_WHITELIST_COLUMNS,
            rows = listOf(listOf("OPPO Enco X4", "453648", 1L)),
        )
        val second = requireNotNull(MelodyProviderMerge.identityOf(envelopeJson(hexId = "06F010")))

        val merged = requireNotNull(
            MelodyProviderMerge.mergeAll(MelodyQueryPath.EARS_WHITELIST, official, listOf(identity, second)),
        )

        assertEquals(3, merged.rows.size)
        assertEquals(listOf("Sony WF-1000XM3", "3296", 0L), merged.rows[1])
        assertEquals(listOf(second.name, second.decimalId, 0L), merged.rows[2])
    }

    @Test
    fun mergeAll_isNullWhenTheHostAlreadyCarriesEveryDevice() {
        val official = MelodyInjectionTable(
            columns = MelodyProviderMerge.ALL_WHITELIST_COLUMNS,
            rows = listOf(listOf("{\"id\":\"000CE0\"}")),
        )

        assertNull(MelodyProviderMerge.mergeAll(MelodyQueryPath.ALL_WHITELIST, official, listOf(identity)))
        assertNull(MelodyProviderMerge.mergeAll(MelodyQueryPath.ALL_WHITELIST, null, emptyList()))
    }

    @Test
    fun allWhitelist_appendsOurContentAndDeduplicatesById() {
        val official = MelodyInjectionTable(
            columns = MelodyProviderMerge.ALL_WHITELIST_COLUMNS,
            rows = listOf(listOf("{\"id\":\"06F010\"}")),
        )

        val appended = requireNotNull(
            MelodyProviderMerge.merge(MelodyQueryPath.ALL_WHITELIST, official, identity),
        )
        assertEquals(2, appended.rows.size)
        assertEquals("000CE0", JsonParser.parse(appended.rows[1][0] as String).asObject()["id"].asString())

        val already = official.copy(rows = official.rows + listOf(listOf("{\"id\":\"000CE0\"}")))
        val unchanged = requireNotNull(
            MelodyProviderMerge.merge(MelodyQueryPath.ALL_WHITELIST, already, identity),
        )
        assertEquals(already.rows, unchanged.rows)
    }

    @Test
    fun wear_appendsTheAddressRowAndDeduplicates() {
        val augmented = MelodyProviderMerge.mergeWear(official = null, mac = "aa:bb:cc:dd:ee:ff", bothInEar = true)

        assertEquals(MelodyProviderMerge.WEAR_COLUMNS, augmented.columns)
        assertEquals(listOf("AA:BB:CC:DD:EE:FF", 1L), augmented.rows.single())

        val again = MelodyProviderMerge.mergeWear(augmented, "AA:BB:CC:DD:EE:FF", bothInEar = false)
        assertEquals(augmented.rows, again.rows)
    }

    @Test
    fun whitelistContent_appendsToWhiteListAndKeepsEveryOtherDoField() {
        val official = MelodyInjectionTable(
            columns = MelodyProviderMerge.WHITELIST_CONTENT_COLUMNS,
            rows = listOf(listOf(gzip(doJson))),
        )

        val merged = requireNotNull(MelodyProviderMerge.merge(MelodyQueryPath.WHITELIST_CONTENT, official, identity))
        val mergedJson = MelodyProviderMerge.gunzip(merged.rows.single()[0] as ByteArray)
        val root = requireNotNull(mergedJson).let { JsonParser.parse(it).asObject() }

        val whiteList = (root["whiteList"] as JsonValue.Array).values
        assertEquals(2, whiteList.size)
        assertEquals("000CE0", (whiteList[1] as JsonValue.Object).values["id"].asString())
        // The rest of the DO survives verbatim.
        assertEquals(7.0, (root["versionCode"] as JsonValue.NumberValue).raw.toDouble(), 0.0)
        assertEquals("https://example.invalid/collect", (root["txMusicCollectUrl"] as JsonValue.StringValue).value)
    }

    @Test
    fun whitelistContent_isIdempotentWhenOurIdIsAlreadyInTheDo() {
        val blob = gzip(doJson.replace("\"0000079A\"", "\"000CE0\""))
        val official = MelodyInjectionTable(
            columns = MelodyProviderMerge.WHITELIST_CONTENT_COLUMNS,
            rows = listOf(listOf(blob)),
        )

        val merged = requireNotNull(MelodyProviderMerge.merge(MelodyQueryPath.WHITELIST_CONTENT, official, identity))

        assertContentEquals(blob, merged.rows.single()[0] as ByteArray)
    }

    @Test
    fun whitelistContent_degradesWhenTheOfficialBlobIsNotReadableOrTooLarge() {
        val notGzip = MelodyInjectionTable(
            columns = MelodyProviderMerge.WHITELIST_CONTENT_COLUMNS,
            rows = listOf(listOf("plain text".toByteArray())),
        )
        assertNull(MelodyProviderMerge.merge(MelodyQueryPath.WHITELIST_CONTENT, notGzip, identity))

        val tooLarge = MelodyInjectionTable(
            columns = MelodyProviderMerge.WHITELIST_CONTENT_COLUMNS,
            rows = listOf(listOf(ByteArray(MelodyProviderMerge.MAX_MERGE_BYTES + 1))),
        )
        assertNull(MelodyProviderMerge.merge(MelodyQueryPath.WHITELIST_CONTENT, tooLarge, identity))
    }

    @Test
    fun wearPathsAreNotHandledByTheWhitelistMerge() {
        assertNull(MelodyProviderMerge.merge(MelodyQueryPath.EARPHONE_BOTH_IN_EAR, null, identity))
        assertNull(MelodyProviderMerge.merge(MelodyQueryPath.ACTIVE_EARPHONE_BOTH_IN_EAR, null, identity))
    }

    @Test
    fun defaultColumnsMatchTheHostSurfaces() {
        assertEquals(
            MelodyProviderMerge.FIND_WHITELIST_COLUMNS,
            MelodyProviderMerge.defaultColumns(MelodyQueryPath.FIND_WHITELIST),
        )
        assertEquals(
            MelodyProviderMerge.EARS_WHITELIST_COLUMNS,
            MelodyProviderMerge.defaultColumns(MelodyQueryPath.EARS_WHITELIST),
        )
        assertEquals(
            MelodyProviderMerge.WEAR_COLUMNS,
            MelodyProviderMerge.defaultColumns(MelodyQueryPath.ACTIVE_EARPHONE_BOTH_IN_EAR),
        )
    }

    @Test
    fun gzipRoundTripIsLossless() {
        val bytes = requireNotNull(MelodyProviderMerge.gzip(doJson))
        assertNotNull(bytes)
        assertEquals(doJson, MelodyProviderMerge.gunzip(bytes))
        assertArrayEquals(bytes, requireNotNull(MelodyProviderMerge.gzip(requireNotNull(MelodyProviderMerge.gunzip(bytes)))))
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) =
        assertTrue("expected ${expected.size} bytes, got ${actual.size}", expected.contentEquals(actual))

    private fun gzip(text: String): ByteArray = requireNotNull(MelodyProviderMerge.gzip(text))

    private fun JsonValue.asObject(): Map<String, JsonValue> =
        (this as? JsonValue.Object)?.values ?: error("expected object, got $this")

    private fun JsonValue?.asString(): String? = (this as? JsonValue.StringValue)?.value

    private fun envelopeJson(hexId: String): String = JsonWriter.write(
        JsonParser.parse(
            """
            {
              "version": 1,
              "mac": "14:3F:A6:02:5F:B0",
              "definition": { "id": "sony.wf1000xm3", "version": "1.0.0" },
              "whitelist": {
                "id": "$hexId",
                "name": "Sony WF-1000XM3",
                "brand": "Sony",
                "type": "T1",
                "uuid": "96cc203e-5068-46ad-b32d-e316f5e069ba",
                "supportSpp": false,
                "function": { "batteryRadix": 10 }
              },
              "panel": { "sectionTitle": "BtRemix", "hideSections": [], "hideKeys": [], "greyKeys": [] }
            }
            """.trimIndent(),
        ),
    )

    private val doJson =
        """{"whiteList":[{"id":"0000079A","name":"OPPO Enco X"}],"leAllFilterFunctions":[],""" +
            """"versionCode":7,"txMusicCollectUrl":"https://example.invalid/collect"}"""

    private val ancEnvelopeJson =
        """
        {
          "version": 1,
          "mac": "14:3F:A6:02:5F:B0",
          "anc": {
            "uiVersion": 2,
            "modes": [
              { "modeType": 5, "protocolIndex": 0, "state": "anc", "label": "Noise canceling" },
              { "modeType": 1, "protocolIndex": 1, "state": "off", "label": "Off" }
            ],
            "modeAction": "anc.setMode",
            "modeParam": "mode",
            "strengthParam": "value",
            "strength": { "state": "ancLevel", "action": "anc.setLevel", "levels": [
              { "modeType": 3, "protocolIndex": 10, "level": 1 },
              { "modeType": 8, "protocolIndex": 11, "level": 10 },
              { "modeType": 4, "protocolIndex": 12, "level": 20 } ] }
          }
        }
        """.trimIndent()
}

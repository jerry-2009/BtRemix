package com.fusion.melodyLinkNeo.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M5.2 acceptance for the `setgate` payload parser (`HANDOFF_MELODY_M5_PLAN.md` §4 M5.2 /
 * `docs/melody-capability-map.md` §8.5). The host writes `type` as either a JSON number or a numeric
 * string, so both must decode; anything else fails open to `chain.proceed()`.
 */
class MelodySetgatePolicyTest {

    @Test
    fun aNumericTypeAndMac_decode() {
        assertEquals(
            MelodySetgatePolicy.Command(mac = MAC, modeType = 5),
            MelodySetgatePolicy.parse("""{"type":5,"mac":"$MAC","product_id":"000CE0"}"""),
        )
    }

    @Test
    fun aStringType_decodesToo() {
        assertEquals(
            MelodySetgatePolicy.Command(mac = MAC, modeType = 1),
            MelodySetgatePolicy.parse("""{"type":"1","mac":"$MAC"}"""),
        )
    }

    @Test
    fun keyOrderAndUnknownKeys_doNotMatter() {
        assertEquals(
            MelodySetgatePolicy.Command(mac = MAC, modeType = 10),
            MelodySetgatePolicy.parse(
                """{"product_name":"WH-1000XM3","mac":"$MAC","product_id":"000CE0","type":"10"}""",
            ),
        )
    }

    @Test
    fun aMissingOrBlankMac_isRejected() {
        assertNull(MelodySetgatePolicy.parse("""{"type":5}"""))
        assertNull(MelodySetgatePolicy.parse("""{"type":5,"mac":""}"""))
        assertNull(MelodySetgatePolicy.parse("""{"type":5,"mac":"   "}"""))
    }

    @Test
    fun aMissingOrNonNumericType_isRejected() {
        assertNull(MelodySetgatePolicy.parse("""{"mac":"$MAC"}"""))
        assertNull(MelodySetgatePolicy.parse("""{"mac":"$MAC","type":"off"}"""))
        assertNull(MelodySetgatePolicy.parse("""{"mac":"$MAC","type":true}"""))
    }

    @Test
    fun aMalformedOrAbsentPayload_isRejected() {
        assertNull(MelodySetgatePolicy.parse(null))
        assertNull(MelodySetgatePolicy.parse(""))
        assertNull(MelodySetgatePolicy.parse("not json"))
        assertNull(MelodySetgatePolicy.parse("[1,2,3]"))
    }

    private companion object {
        const val MAC = "14:3F:A6:02:5F:B0"
    }
}

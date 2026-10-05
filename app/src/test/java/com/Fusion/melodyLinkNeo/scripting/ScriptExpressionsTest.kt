package com.fusion.melodyLinkNeo.scripting

import com.fusion.melodyLinkNeo.definition.json.JsonParser
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import com.fusion.melodyLinkNeo.scripting.api.ScriptCodec
import com.fusion.melodyLinkNeo.scripting.api.evaluateScriptExpression
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expression-level coverage for the declarative decode language.
 *
 * `len` exists because firmware often answers one parameter with differently shaped payloads: the
 * decode has to choose the value position from the frame length instead of assuming one shape.
 */
class ScriptExpressionsTest {
    @Test
    fun len_returnsTheByteCountOfAFrame() = runBlocking {
        assertEquals(
            StateValue.IntValue(4),
            evaluate("""{"len":{"var":"raw"}}""", byteArrayOf(1, 2, 3, 4)),
        )
    }

    @Test
    fun decode_picksTheValuePositionFromTheFrameLength() = runBlocking {
        val expression = """
            {"if":[
              {"eq":[{"len":{"var":"raw"}},4]},
              {"eq":[{"at":[{"var":"raw"},3]},1]},
              {"eq":[{"at":[{"var":"raw"},2]},1]}
            ]}
        """.trimIndent()

        // 4-byte shape: [return, parameter, reserved, value]
        assertEquals(StateValue.BooleanValue(true), evaluate(expression, byteArrayOf(0xE7.toByte(), 0x02, 0x00, 0x01)))
        assertEquals(StateValue.BooleanValue(false), evaluate(expression, byteArrayOf(0xE7.toByte(), 0x02, 0x00, 0x00)))
        // 3-byte shape: [return, parameter, value]
        assertEquals(StateValue.BooleanValue(true), evaluate(expression, byteArrayOf(0xE7.toByte(), 0x01, 0x01)))
    }

    private suspend fun evaluate(json: String, raw: ByteArray): StateValue = evaluateScriptExpression(
        expression = ScriptCodec.parseExpression(JsonParser.parse(json), "test"),
        args = emptyMap(),
        variables = mapOf("raw" to StateValue.BytesValue(raw)),
    )
}

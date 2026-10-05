package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.device.runtime.StateValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.4 acceptance for the click-link argument builder: every「高级功能」row kind rebuilds exactly the
 * `DeviceAction` argument the Compose renderer would have sent, and an unbuildable row returns `null`
 * so the host logs and skips instead of firing a partial action.
 */
class MelodyPanelActionArgsTest {

    @Test
    fun switch_carriesTheFlippedBooleanUnderTheDeclaredParameter() {
        val row = row(MelodyPanelRowKind.SWITCH, action = "upscaling.set", state = "upscaling", param = "value")

        assertEquals(mapOf("value" to StateValue.BooleanValue(false)), MelodyPanelActionArgs.switch(row, false))
        assertEquals(mapOf("value" to StateValue.BooleanValue(true)), MelodyPanelActionArgs.switch(row, true))
    }

    @Test
    fun switch_withoutADeclaredParameter_fallsBackToValue() {
        val row = row(MelodyPanelRowKind.SWITCH, action = "flag.set", state = "flag")

        assertEquals(mapOf("value" to StateValue.BooleanValue(true)), MelodyPanelActionArgs.switch(row, true))
    }

    @Test
    fun switch_withoutAnAction_isNotExecutable() {
        assertNull(MelodyPanelActionArgs.switch(row(MelodyPanelRowKind.SWITCH, action = null, state = "flag"), true))
    }

    @Test
    fun choice_usesTheOptionValueNotItsLabel() {
        val row = row(
            MelodyPanelRowKind.SEGMENTED,
            action = "eq.set",
            state = "eqPreset",
            param = "preset",
            valueType = MelodyPanelValueType.ENUM,
        )

        assertEquals(mapOf("preset" to StateValue.StringValue("bass")), MelodyPanelActionArgs.choice(row, "bass"))
    }

    @Test
    fun slider_quantizesToTheIntegerGridAndClamps() {
        val row = row(
            MelodyPanelRowKind.SLIDER,
            action = "level.set",
            state = "level",
            param = "value",
            valueType = MelodyPanelValueType.INTEGER,
            min = 1.0,
            max = 20.0,
            step = 1.0,
        )

        assertEquals(mapOf("value" to StateValue.IntValue(10)), MelodyPanelActionArgs.slider(row, 10.4))
        assertEquals(mapOf("value" to StateValue.IntValue(20)), MelodyPanelActionArgs.slider(row, 100.0))
        assertEquals(mapOf("value" to StateValue.IntValue(1)), MelodyPanelActionArgs.slider(row, -5.0))
    }

    @Test
    fun slider_onANumberState_keepsAFractionUnderTheStepGrid() {
        val row = row(
            MelodyPanelRowKind.SLIDER,
            action = "volume.set",
            state = "volume",
            param = "value",
            valueType = MelodyPanelValueType.NUMBER,
            min = 0.0,
            max = 1.0,
            step = 0.25,
        )

        assertEquals(mapOf("value" to StateValue.DoubleValue(0.5)), MelodyPanelActionArgs.slider(row, 0.6))
    }

    @Test
    fun slider_withoutBounds_usesZeroToHundred() {
        val row = row(MelodyPanelRowKind.SLIDER, action = "level.set", state = "level", param = "value")

        assertEquals(mapOf("value" to StateValue.DoubleValue(42.0)), MelodyPanelActionArgs.slider(row, 42.0))
        assertNull(MelodyPanelActionArgs.slider(row, Double.NaN))
    }

    @Test
    fun button_rebuildsEveryTypedArgument() {
        val row = row(
            MelodyPanelRowKind.BUTTON,
            action = "device.find",
            args = mapOf(
                "count" to MelodyPanelArg(MelodyPanelArgType.INT, "3"),
                "flag" to MelodyPanelArg(MelodyPanelArgType.BOOLEAN, "true"),
                "name" to MelodyPanelArg(MelodyPanelArgType.STRING, "abc"),
                "raw" to MelodyPanelArg(MelodyPanelArgType.BYTES, "0A0b"),
            ),
        )

        assertEquals(
            mapOf(
                "count" to StateValue.IntValue(3),
                "flag" to StateValue.BooleanValue(true),
                "name" to StateValue.StringValue("abc"),
                "raw" to StateValue.BytesValue(byteArrayOf(10, 11)),
            ),
            MelodyPanelActionArgs.button(row),
        )
    }

    @Test
    fun button_withNoArgs_isAnEmptyAction() {
        val row = row(MelodyPanelRowKind.BUTTON, action = "device.rescan")

        assertEquals(emptyMap<String, StateValue>(), MelodyPanelActionArgs.button(row))
    }

    @Test
    fun button_withAnUnreadableArgument_isNotExecutable() {
        val row = row(
            MelodyPanelRowKind.BUTTON,
            action = "device.find",
            args = mapOf("count" to MelodyPanelArg(MelodyPanelArgType.INT, "not-a-number")),
        )

        assertNull(MelodyPanelActionArgs.button(row))
    }

    @Test
    fun readOnlyKinds_areNeverExecutable() {
        val row = row(MelodyPanelRowKind.VALUE, action = null, state = "level")

        assertNull(MelodyPanelActionArgs.switch(row, true))
        assertNull(MelodyPanelActionArgs.slider(row, 1.0))
    }

    @Test
    fun argOf_rejectsStructuredValuesAndEncodesScalars() {
        assertNull(MelodyPanelActionArgs.argOf(StateValue.ListValue(emptyList())))
        assertNull(MelodyPanelActionArgs.argOf(StateValue.MapValue(emptyMap())))
        assertEquals(MelodyPanelArg(MelodyPanelArgType.BYTES, "0a0b"), MelodyPanelActionArgs.argOf(StateValue.BytesValue(byteArrayOf(10, 11))))
        assertEquals(MelodyPanelArg(MelodyPanelArgType.DOUBLE, "1.5"), MelodyPanelActionArgs.argOf(StateValue.DoubleValue(1.5)))
    }

    @Test
    fun paramName_usesTheFallbackOnlyForABlankDeclaration() {
        assertEquals("enabled", MelodyPanelActionArgs.paramName("enabled"))
        assertEquals("value", MelodyPanelActionArgs.paramName(null))
        assertEquals("value", MelodyPanelActionArgs.paramName("   "))
    }

    @Test
    fun truthy_matchesTheHostSwitchSpellings() {
        assertTrue(MelodyPanelActionArgs.isTruthy("true"))
        assertTrue(MelodyPanelActionArgs.isTruthy("1"))
        assertTrue(MelodyPanelActionArgs.isTruthy("ON"))
        assertFalse(MelodyPanelActionArgs.isTruthy("false"))
        assertFalse(MelodyPanelActionArgs.isTruthy(null))
    }

    private fun row(
        kind: MelodyPanelRowKind,
        action: String?,
        state: String? = null,
        param: String? = null,
        valueType: String? = null,
        min: Double? = null,
        max: Double? = null,
        step: Double? = null,
        args: Map<String, MelodyPanelArg> = emptyMap(),
    ): MelodyPanelRow = MelodyPanelRow(
        kind = kind,
        key = "melody_bridge_test",
        title = "Test",
        state = state,
        action = action,
        param = param,
        valueType = valueType,
        args = args,
        min = min,
        max = max,
        step = step,
    )
}

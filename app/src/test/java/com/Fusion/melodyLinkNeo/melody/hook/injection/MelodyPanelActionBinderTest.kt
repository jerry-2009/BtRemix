package com.fusion.melodyLinkNeo.melody.hook.injection

import com.fusion.melodyLinkNeo.device.runtime.StateValue
import com.fusion.melodyLinkNeo.melody.api.MelodyBridgeResult
import com.fusion.melodyLinkNeo.melody.api.MelodyPanelArg
import com.fusion.melodyLinkNeo.melody.api.MelodyPanelArgType
import com.fusion.melodyLinkNeo.melody.api.MelodyPanelRow
import com.fusion.melodyLinkNeo.melody.api.MelodyPanelRowKind
import com.fusion.melodyLinkNeo.melody.api.MelodyPanelValueType
import com.coui.appcompat.preference.COUIMenuPreference
import com.coui.appcompat.preference.COUIPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.4 acceptance for the row click link (`HANDOFF_MELODY_M4_PLAN.md` §3 M4.4, spec §7.4): the listener
 * is attached through the host's setter shape, the write is `actionId -> args` exactly as the Compose
 * renderer builds it, a MAC mismatch is dropped, and no failure path throws.
 */
class MelodyPanelActionBinderTest {

    @Test
    fun switchRow_flipsTheLiveValueAndExecutes() {
        val preference = COUIPreference("melody_bridge_upscaling")
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()

        assertTrue(bind(preference, switchRow(), executor = executor(calls), stateText = { "false" }))
        assertTrue(preference.performClick())

        assertEquals(listOf("upscaling.set"), calls.map { it.first })
        assertEquals(mapOf("value" to StateValue.BooleanValue(true)), calls[0].second)
    }

    @Test
    fun switchRow_withoutALiveValue_fallsBackToTheHostCheckedState() {
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()
        val switch = com.coui.appcompat.preference.COUISwitchPreference()
        switch.setChecked(true)

        bind(switch, switchRow(), executor = executor(calls), stateText = { null })
        switch.performClick()

        assertEquals(mapOf("value" to StateValue.BooleanValue(false)), calls.single().second)
    }

    @Test
    fun segmentedRow_opensThePickerAndExecutesThePickedOption() {
        val preference = COUIPreference("melody_bridge_eqPreset")
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()
        var shown = false
        val choice = MelodyPanelChoicePresenter { _, _, items, checked, onPick ->
            shown = true
            assertEquals(listOf("Off", "Bright"), items)
            assertEquals(1, checked)
            onPick(0)
            true
        }

        bind(preference, segmentedRow(), executor = executor(calls), stateText = { "bright" }, choice = choice)
        preference.performClick()

        assertTrue(shown)
        assertEquals("eq.set", calls.single().first)
        // The option *value* travels, not the label.
        assertEquals(mapOf("preset" to StateValue.StringValue("off")), calls.single().second)
    }

    @Test
    fun menuChoiceRow_executesTheValuePickedInTheHostPopup() {
        val preference = menuPreference()
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()

        assertTrue(bind(preference, segmentedRow(), executor = executor(calls), stateText = { "off" }))
        assertTrue(preference.select(1))

        assertEquals("eq.set", calls.single().first)
        // The option *value* travels (not the label), exactly like the picker path.
        assertEquals(mapOf("preset" to StateValue.StringValue("bright")), calls.single().second)
        // Accepting the pick marks the entry and refreshes the value on the right.
        assertEquals("bright", preference.currentValue())
        assertEquals("Bright", preference.getAssignment())
    }

    @Test
    fun menuChoiceRow_dropsAnOptionTheRowDoesNotDeclare() {
        val preference = COUIMenuPreference()
        preference.f(arrayOf<CharSequence>("Off", "Turbo"))
        preference.g(arrayOf<CharSequence>("off", "turbo"))
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()
        val logs = mutableListOf<String>()

        bind(preference, segmentedRow(), executor = executor(calls), stateText = { "off" }, log = capturing(logs))

        assertFalse(preference.select(1))
        assertTrue(calls.isEmpty())
        assertTrue(logs.any { it.contains("option_unknown") })
    }

    @Test
    fun menuChoiceRow_withAMacMismatch_dropsTheWrite() {
        val preference = menuPreference()
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()
        val logs = mutableListOf<String>()

        bind(
            preference,
            segmentedRow(),
            currentMac = { "AA:BB:CC:DD:EE:FF" },
            executor = executor(calls),
            stateText = { "off" },
            log = capturing(logs),
        )

        assertFalse(preference.select(1))
        assertTrue(calls.isEmpty())
        assertTrue(logs.any { it.contains("mac_mismatch") })
    }

    @Test
    fun sliderRow_opensTheSliderAndExecutesThePickedValue() {
        val preference = COUIPreference("melody_bridge_level")
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()
        var received: Double? = null
        val logs = mutableListOf<String>()
        val slider = MelodyPanelSliderPresenter { _, _, min, max, _, value, _, onPick ->
            assertEquals(1.0, min, 0.0)
            assertEquals(20.0, max, 0.0)
            received = value
            onPick(12.4)
            true
        }

        bind(preference, sliderRow(), executor = executor(calls), stateText = { "7" }, slider = slider, log = capturing(logs))
        preference.performClick()

        assertEquals(7.0, received ?: -1.0, 0.0)
        assertEquals(logs.joinToString("\n"), 1, calls.size)
        assertEquals(mapOf("value" to StateValue.IntValue(12)), calls.single().second)
    }

    @Test
    fun buttonRow_sendsItsTypedArguments() {
        val preference = COUIPreference("melody_bridge_find")
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()
        val row = MelodyPanelRow(
            kind = MelodyPanelRowKind.BUTTON,
            key = "melody_bridge_find",
            title = "Find",
            action = "device.find",
            args = mapOf("count" to MelodyPanelArg(MelodyPanelArgType.INT, "3")),
        )

        bind(preference, row, executor = executor(calls))
        preference.performClick()

        assertEquals("device.find", calls.single().first)
        assertEquals(mapOf("count" to StateValue.IntValue(3)), calls.single().second)
    }

    @Test
    fun aMacMismatch_dropsTheWrite() {
        val preference = COUIPreference("melody_bridge_upscaling")
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()
        val logs = mutableListOf<String>()

        bind(
            preference,
            switchRow(),
            currentMac = { "AA:BB:CC:DD:EE:FF" },
            executor = executor(calls),
            stateText = { "false" },
            log = capturing(logs),
        )
        preference.performClick()

        assertTrue(calls.isEmpty())
        assertTrue(logs.any { it.contains("mac_mismatch") })
    }

    @Test
    fun anUnavailableRow_doesNothing() {
        val preference = COUIPreference("melody_bridge_upscaling")
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()

        bind(preference, switchRow().copy(unavailable = true), executor = executor(calls), stateText = { "false" })
        preference.performClick()

        assertTrue(calls.isEmpty())
    }

    @Test
    fun aReadOnlyRow_neverExecutes() {
        val preference = COUIPreference("melody_bridge_level")
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()
        val row = MelodyPanelRow(
            kind = MelodyPanelRowKind.VALUE,
            key = "melody_bridge_level",
            title = "Level",
            state = "level",
        )

        bind(preference, row, executor = executor(calls))
        preference.performClick()

        assertTrue(calls.isEmpty())
    }

    @Test
    fun aFailedExecute_onlyLogs() {
        val preference = COUIPreference("melody_bridge_upscaling")
        val logs = mutableListOf<String>()
        val executor = MelodyPanelExecutor { _, _, onResult -> onResult(MelodyBridgeResult.ERROR_ACTION_FAILED) }

        bind(preference, switchRow(), executor = executor, stateText = { "false" }, log = capturing(logs))
        preference.performClick()

        assertTrue(logs.any { it.contains("reason=failed") || it.contains("failed") })
    }

    @Test
    fun aDoubleTap_isSuppressedWhileTheFirstActionIsInFlight() {
        val preference = COUIPreference("melody_bridge_upscaling")
        var calls = 0
        val executor = MelodyPanelExecutor { _, _, _ -> calls++ }

        bind(preference, switchRow(), executor = executor, stateText = { "false" })
        preference.performClick()
        preference.performClick()

        assertEquals(1, calls)
    }

    @Test
    fun aPickerThatCannotOpen_skipsWithoutThrowing() {
        val preference = COUIPreference("melody_bridge_eqPreset")
        val calls = mutableListOf<Pair<String, Map<String, StateValue>>>()
        val logs = mutableListOf<String>()
        val choice = MelodyPanelChoicePresenter { _, _, _, _, _ -> false }

        bind(
            preference,
            segmentedRow(),
            executor = executor(calls),
            stateText = { "off" },
            choice = choice,
            log = capturing(logs),
        )
        preference.performClick()

        assertTrue(calls.isEmpty())
        assertTrue(logs.any { it.contains("picker_failed") })
    }

    @Test
    fun bind_withoutAReachableSetter_failsOpen() {
        val logs = mutableListOf<String>()

        val attached = MelodyPanelActionBinder.bind(
            preference = Any(),
            row = switchRow(),
            context = null,
            mac = MAC,
            currentMac = { MAC },
            stateText = MelodyRowStateText { null },
            executor = MelodyPanelExecutor { _, _, _ -> },
            choicePresenter = MelodyPanelChoicePresenter { _, _, _, _, _ -> false },
            sliderPresenter = MelodyPanelSliderPresenter { _, _, _, _, _, _, _, _ -> false },
            inFlight = HashMap(),
            loader = requireNotNull(javaClass.classLoader),
            log = capturing(logs),
        )

        assertFalse(attached)
        assertTrue(logs.any { it.contains("no_listener") })
    }

    // --- drivers ----------------------------------------------------------------------------------

    private fun bind(
        preference: COUIPreference,
        row: MelodyPanelRow,
        currentMac: () -> String? = { MAC },
        stateText: (String) -> String? = { null },
        executor: MelodyPanelExecutor = MelodyPanelExecutor { _, _, _ -> },
        choice: MelodyPanelChoicePresenter = MelodyPanelChoicePresenter { _, _, _, _, _ -> false },
        slider: MelodyPanelSliderPresenter = MelodyPanelSliderPresenter { _, _, _, _, _, _, _, _ -> false },
        log: MelodyGroupLog = MelodyGroupLog { _, _ -> },
    ): Boolean = MelodyPanelActionBinder.bind(
        preference = preference,
        row = row,
        context = null,
        mac = MAC,
        currentMac = currentMac,
        stateText = MelodyRowStateText(stateText),
        executor = executor,
        choicePresenter = choice,
        sliderPresenter = slider,
        inFlight = HashMap(),
        loader = requireNotNull(javaClass.classLoader),
        log = log,
    )

    private fun executor(calls: MutableList<Pair<String, Map<String, StateValue>>>): MelodyPanelExecutor =
        MelodyPanelExecutor { action, args, _ -> calls += action to args }

    private fun capturing(logs: MutableList<String>): MelodyGroupLog = MelodyGroupLog { name, fields ->
        logs += name + fields.joinToString(" ", prefix = " ") { "${it.first}=${it.second}" }
    }

    private fun switchRow(): MelodyPanelRow = MelodyPanelRow(
        kind = MelodyPanelRowKind.SWITCH,
        key = "melody_bridge_upscaling",
        title = "DSEE HX upscaling",
        state = "upscaling",
        action = "upscaling.set",
        param = "value",
        valueType = MelodyPanelValueType.BOOLEAN,
    )

    private fun segmentedRow(): MelodyPanelRow = MelodyPanelRow(
        kind = MelodyPanelRowKind.SEGMENTED,
        key = "melody_bridge_eqPreset",
        title = "Equalizer",
        state = "eqPreset",
        action = "eq.set",
        param = "preset",
        valueType = MelodyPanelValueType.ENUM,
        options = listOf("off", "bright"),
        optionLabels = listOf("Off", "Bright"),
    )

    private fun sliderRow(): MelodyPanelRow = MelodyPanelRow(
        kind = MelodyPanelRowKind.SLIDER,
        key = "melody_bridge_level",
        title = "Level",
        state = "level",
        action = "level.set",
        param = "value",
        valueType = MelodyPanelValueType.INTEGER,
        min = 1.0,
        max = 20.0,
        step = 1.0,
    )

    private companion object {
        const val MAC = "14:3F:A6:02:5F:B0"
    }

    /** A host-shaped popup row carrying the equalizer entries the applier would program. */
    private fun menuPreference(): COUIMenuPreference = COUIMenuPreference().apply {
        setKey("melody_bridge_eqPreset")
        f(arrayOf<CharSequence>("Off", "Bright"))
        g(arrayOf<CharSequence>("off", "bright"))
    }
}

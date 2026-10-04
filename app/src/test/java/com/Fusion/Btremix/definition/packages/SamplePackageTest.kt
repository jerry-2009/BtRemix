package com.Fusion.Btremix.definition.packages

import com.Fusion.Btremix.definition.api.UiNode
import com.Fusion.Btremix.definition.api.DefinitionSchema
import com.Fusion.Btremix.definition.api.TransportType
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Validates every shipped sample device package with the real reader + validator so a regenerated
 * sample cannot silently drift from the package format. Skips when the samples directory is absent
 * (for example a stripped checkout).
 */
class SamplePackageTest {
    @Test
    fun shippedSamples_areValidPackages() {
        val files = samplePackageFiles()
        assumeTrue("no sample device package found next to the checkout", files.isNotEmpty())

        val packages = files.associate { file ->
            val devicePackage = DevicePackageValidator().validate(DevicePackageReader().read(file))
            assertEquals("package.json id must match definition.json id", devicePackage.packageId, devicePackage.definition.id)
            assertTrue("${devicePackage.packageId} has an empty ui schema", devicePackage.definition.ui.children.isNotEmpty())
            devicePackage.packageId to devicePackage
        }

        assertTrue("expected the Auralite sample", "demo.auralite" in packages)
        assertTrue("expected the Cleer ARC 3 sample", "cleer.arc3" in packages)
    }

    @Test
    fun cleerArc3Sample_exposesTheExpectedUiNodesAndScriptFreeActions() {
        val file = samplePackageFiles().firstOrNull { it.name.startsWith("cleer.arc3-") }
        assumeTrue("no Cleer ARC 3 sample device package found", file != null)
        val definition = DevicePackageValidator().validate(DevicePackageReader().read(requireNotNull(file))).definition

        val nodes = definition.ui.children.flatMap(::flatten).toList()
        val nodeTypes = nodes.map(::typeName)
        listOf("section", "value", "progress", "text", "segmented", "slider", "switch", "button").forEach { expected ->
            assertTrue("ui schema is missing a $expected node", expected in nodeTypes)
        }
        // Each battery is a single self-contained meter row: a bare bar next to a `value` node for
        // the same state reads as if it belonged to the following row.
        val progressStates = nodes.filterIsInstance<UiNode.Progress>().map { it.state }
        assertTrue(
            "expected battery meters for left/right/case but was $progressStates",
            progressStates.containsAll(listOf("battery.left", "battery.right", "battery.case")),
        )
        val valueStates = nodes.filterIsInstance<UiNode.Value>().map { it.state }.toSet()
        assertTrue(
            "a state must not be rendered as both value and progress: ${progressStates intersect valueStates}",
            (progressStates.toSet() intersect valueStates).isEmpty(),
        )
        listOf("ancMode", "ancLevel", "eqPreset", "spatialAudio", "volume", "gameMode", "multipoint", "wearDetection").forEach { state ->
            assertTrue("missing state $state", state in definition.states)
        }
        // The package is UI-only on purpose: no action may carry a script yet.
        assertTrue(definition.actions.values.all { it.script == null })
        assertNotNull(definition.actions["device.find"])
    }

    /**
     * The WF-1000XM3 package must be pure configuration: classic SPP transport, generic "framed"
     * codec with the frame layout as data, payload-type matching and no script action. Battery/ANC
     * use the V1 command numbers the unit actually answers (see HANDOFF_SONY_XM3_BATTERY_ANC.md).
     */
    @Test
    fun sonyWf1000Xm3Sample_isClassicSppFramingAsConfiguration() {
        // The package version is part of the file name; the newest sample is the one that carries the
        // current M4.3b `melody.anc` contract.
        val file = samplePackageFiles().filter { it.name.startsWith("sony.wf1000xm3-") }.maxByOrNull { it.name }
        assumeTrue("no Sony WF-1000XM3 sample device package found", file != null)
        val definition = DevicePackageValidator().validate(DevicePackageReader().read(requireNotNull(file))).definition

        // M3.1: the sample now advertises itself to ColorOS Melody, which is schema version 4.
        assertEquals(DefinitionSchema.VERSION_MELODY, definition.manifest.schemaVersion)
        val melody = requireNotNull(definition.melody)
        assertEquals("Sony WF-1000XM3", melody.support.name)
        assertEquals("Sony", melody.support.brand)
        // 0x0CE0 is Sony's modalias product id for the unit (v054Cp0CE0), stored decimal.
        assertEquals("3296", melody.support.productId)
        assertEquals("96cc203e-5068-46ad-b32d-e316f5e069ba", melody.support.uuid)
        assertFalse("Melody must not open its own SPP channel", melody.support.supportSpp)
        val transport = requireNotNull(definition.protocol.transport)
        assertEquals(TransportType.RFCOMM, transport.type)
        // The bonded WF-1000XM3 advertises this SPP service; 956c7b26 is not in its SDP record.
        assertEquals("96cc203e-5068-46ad-b32d-e316f5e069ba", transport.service)
        assertEquals(null, transport.characteristic)

        val framing = requireNotNull(definition.protocol.framing)
        assertEquals("framed", framing.codec)
        assertEquals(0x3E, requireNotNull(framing.header))
        assertEquals(0x3C, requireNotNull(framing.trailer))
        assertEquals("sum8", framing.checksum)
        assertEquals(listOf(0x0C, 0x0E), framing.acknowledgeMessageTypes)
        assertEquals(0x01, requireNotNull(framing.acknowledgeReplyMessageType))

        assertEquals(setOf(0x11, 0x13), definition.states.getValue("battery.left").notify?.payloadTypes)
        assertEquals(setOf(0x11, 0x13), definition.states.getValue("battery.case").notify?.payloadTypes)
        val dualBattery = definition.protocol.transactions.getValue("battery.dual.get")
        assertEquals(setOf(0x11, 0x13), dualBattery.expectedPayloadTypes)
        // A V1 battery GET is [0x10, type]; a literal payload also lets `initialize` re-read it.
        assertArrayEquals(byteArrayOf(0x10, 0x01), dualBattery.requestPayload)
        assertArrayEquals(byteArrayOf(0x10, 0x02), definition.protocol.transactions.getValue("battery.case.get").requestPayload)

        // ANC is readable: the V1 GET uses asmType 0x02 and its reply feeds the state bindings.
        val ancGet = definition.protocol.transactions.getValue("anc.get")
        assertEquals(setOf(0x67, 0x69), ancGet.expectedPayloadTypes)
        assertArrayEquals(byteArrayOf(0x66, 0x02), ancGet.requestPayload)
        assertEquals(setOf(0x67, 0x69), definition.states.getValue("ancMode").notify?.payloadTypes)
        assertNotNull(definition.states.getValue("ancLevel").notify?.decode)
        assertEquals(8, definition.protocol.messages.getValue("setAnc").fields.size)
        // The peer only acknowledges the SET, so every ANC write re-reads the state afterwards.
        assertEquals(listOf("anc.get"), definition.actions.getValue("anc.setMode").refresh)
        assertEquals(listOf("anc.get"), definition.actions.getValue("anc.setLevel").refresh)

        // DSEE uses the V1 parameter id 0x02 with the 4-byte layout (0xE6 0x02 / 0xE8 0x02 0x00 v).
        val audioGet = definition.protocol.transactions.getValue("audio.get")
        assertArrayEquals(byteArrayOf(0xE6.toByte(), 0x02), audioGet.requestPayload)
        assertEquals(4, definition.protocol.messages.getValue("setUpscaling").fields.size)
        assertEquals(listOf("audio.get"), definition.actions.getValue("upscaling.set").refresh)
        // Replies are decoded by length so a 3-byte (V2-shaped) frame still resolves.
        assertNotNull(definition.states.getValue("upscaling").notify?.condition)
        assertNotNull(definition.states.getValue("upscaling").notify?.decode)

        // The state is populated as soon as the session is ready, not only after a manual refresh.
        listOf("battery.dual.get", "battery.case.get", "anc.get").forEach { transaction ->
            assertTrue("initialize should run $transaction", transaction in definition.protocol.initialize)
        }

        assertTrue("SPP definitions cannot use scripts", definition.actions.values.all { it.script == null })
        listOf("battery.refresh", "battery.case.refresh", "anc.refresh", "anc.setMode", "anc.setLevel", "eq.set", "upscaling.set").forEach { action ->
            assertNotNull("missing action $action", definition.actions[action]?.transaction)
        }
        listOf("battery.left", "battery.right", "battery.case", "ancMode", "ancLevel", "eqPreset", "upscaling")
            .forEach { assertTrue("missing state $it", it in definition.states) }

        // M4.3b: the shipped package carries the native ANC contract - the host mode table, the
        // three-position strength mapping and a `noise` group that must stay visible.
        assertEquals(1, melody.anc.uiVersion)
        assertEquals(listOf(5, 1, 2, 10), melody.anc.modes.map { it.modeType })
        assertEquals(listOf(0, 1, 2, 3), melody.anc.modes.map { it.protocolIndex })
        assertEquals("Wind noise reduction", melody.anc.modes.last().label)
        val strength = requireNotNull(melody.anc.strength)
        assertEquals("ancLevel", strength.state)
        assertEquals("anc.setLevel", strength.action)
        assertEquals(listOf(1, 10, 20), strength.levels.map { it.level })
        // Native「降噪效果」positions: Low / Moderate / High.
        assertEquals(listOf(3, 8, 4), strength.levels.map { it.modeType })
        assertEquals(listOf(10, 11, 12), strength.levels.map { it.protocolIndex })
        assertTrue("the native noise group must stay visible", "noise" !in melody.panel.hideSections)
    }

    private fun flatten(node: UiNode): List<UiNode> = listOf(node) +
        when (node) {
            is UiNode.Column -> node.children.flatMap(::flatten)
            is UiNode.Section -> node.children.flatMap(::flatten)
            else -> emptyList()
        }

    private fun typeName(node: UiNode): String = when (node) {
        is UiNode.Column -> "column"
        is UiNode.Section -> "section"
        is UiNode.Text -> "text"
        is UiNode.Value -> "value"
        is UiNode.Switch -> "switch"
        is UiNode.Slider -> "slider"
        is UiNode.Button -> "button"
        is UiNode.Segmented -> "segmented"
        is UiNode.Progress -> "progress"
    }

    private fun samplePackageFiles(): List<File> = CANDIDATES
        .asSequence()
        .flatMap { directory -> directory.listFiles().orEmpty().asSequence() }
        .filter { it.isFile && it.name.endsWith(".dcpkg", ignoreCase = true) }
        .sortedBy { it.name }
        .toList()

    private companion object {
        private val CANDIDATES = listOf(
            File("../../samples"),
            File("../samples"),
            File("samples"),
        )
    }
}

package com.Fusion.Btremix.definition

import com.Fusion.Btremix.definition.loader.DefinitionLoader
import com.Fusion.Btremix.definition.api.DefinitionSchema
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in definition is loaded from APK assets at runtime, so a broken JSON there would only
 * show up as a package error on device. This test loads the shipped file and validates it.
 */
class DemoDefinitionAssetTest {
    @Test
    fun demoFusionAsset_isValidAndDeclarativeWithOneScriptEscapeHatch() {
        val definition = DefinitionLoader().load(demoDefinitionFile().readText())

        assertEquals("demo.fusion", definition.id)
        assertEquals("1.1.0", definition.manifest.version)
        assertEquals(DefinitionSchema.VERSION_DECLARATIVE, definition.manifest.schemaVersion)
        assertTrue(definition.states.keys.containsAll(listOf("battery", "ancMode", "muted", "volume")))
        assertNotNull("battery must be notification-driven", definition.states.getValue("battery").notify)
        assertNotNull("protocol transport is required for declarative actions", definition.protocol.transport)
        listOf("volume.set", "anc.setMode", "mute.set").forEach { actionId ->
            val action = definition.actions[actionId]
            assertNotNull("missing action $actionId", action)
            assertNotNull("action $actionId has no transaction", action!!.transaction)
            assertTrue("action $actionId has no argument mapping", action.arguments.isNotEmpty())
            assertNull("action $actionId must not mix script and transaction", action.script)
        }
        val escapeHatch = definition.actions.getValue("battery.sync")
        assertNotNull("the script escape hatch must stay available", escapeHatch.script)
        assertTrue(escapeHatch.script!!.steps.isNotEmpty())
    }

    private fun demoDefinitionFile(): File = CANDIDATES.firstOrNull(File::isFile)
        ?: error("cannot locate demo-fusion.json from ${File("").absolutePath}")

    private companion object {
        private val CANDIDATES = listOf(
            File("src/main/assets/definitions/demo-fusion.json"),
            File("app/src/main/assets/definitions/demo-fusion.json"),
            File("btremix/app/src/main/assets/definitions/demo-fusion.json"),
        )
    }
}

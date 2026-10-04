package com.Fusion.Btremix.melody.projection

import com.Fusion.Btremix.definition.json.JsonValue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.1 acceptance: the capability-location experiment must be drivable from one text file without
 * touching the shipped rule table, and must be inert outside debug builds.
 */
class MelodyCapabilityDebugTest {

    @Test
    fun parse_readsScalarArrayAndStringValues() {
        val overrides = MelodyCapabilityDebug.parse(
            """
            # M4.1 round 1
            spatialTypes=[0,1]
            equalizer=1
            highToneQuality = 2
            spatialDescriptionType=-1
            support3DModel=true
            """.trimIndent(),
        )

        assertEquals(
            JsonValue.Array(listOf(JsonValue.NumberValue("0"), JsonValue.NumberValue("1"))),
            overrides["spatialTypes"],
        )
        assertEquals(JsonValue.NumberValue("1"), overrides["equalizer"])
        assertEquals(JsonValue.NumberValue("2"), overrides["highToneQuality"])
        assertEquals(JsonValue.NumberValue("-1"), overrides["spatialDescriptionType"])
        assertEquals(JsonValue.BooleanValue(true), overrides["support3DModel"])
        assertEquals(5, overrides.size)
    }

    @Test
    fun parse_bareKeyAndEmptyValueMeanTurnTheBitOn() {
        val overrides = MelodyCapabilityDebug.parse("phoneSpatialControl\nheadMotion=")

        assertEquals(JsonValue.NumberValue("1"), overrides["phoneSpatialControl"])
        assertEquals(JsonValue.NumberValue("1"), overrides["headMotion"])
    }

    @Test
    fun parse_treatsNonJsonTextAsARawString() {
        val overrides = MelodyCapabilityDebug.parse("customEqUiVersion=T1")

        assertEquals(JsonValue.StringValue("T1"), overrides["customEqUiVersion"])
    }

    @Test
    fun parse_ignoresBlankLinesCommentsAndEmptyInput() {
        assertTrue(MelodyCapabilityDebug.parse(null).isEmpty())
        assertTrue(MelodyCapabilityDebug.parse("").isEmpty())
        assertTrue(MelodyCapabilityDebug.parse("   \n# nothing here\n\t\n").isEmpty())
    }

    @Test
    fun parse_keepsTheLastValueWhenAKeyRepeats() {
        val overrides = MelodyCapabilityDebug.parse("equalizer=1\nequalizer=4")

        assertEquals(JsonValue.NumberValue("4"), overrides["equalizer"])
    }

    @Test
    fun read_isEmptyOutsideDebugBuildsEvenWhenTheFileExists() {
        val file = File.createTempFile("melody-capability", ".txt")
        try {
            file.writeText("spatialTypes=[0,1]\n")

            assertTrue(MelodyCapabilityDebug.read(debugBuild = false, file = file).isEmpty())
            assertEquals(
                JsonValue.Array(listOf(JsonValue.NumberValue("0"), JsonValue.NumberValue("1"))),
                MelodyCapabilityDebug.read(debugBuild = true, file = file)["spatialTypes"],
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun read_missingFileStaysEmpty() {
        assertTrue(
            MelodyCapabilityDebug.read(
                debugBuild = true,
                file = File("build/tmp/does-not-exist-melody-capability.txt"),
            ).isEmpty(),
        )
    }
}

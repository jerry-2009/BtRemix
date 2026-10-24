package com.Fusion.Btremix.definition

import com.Fusion.Btremix.definition.json.DefinitionJsonCodec
import com.Fusion.Btremix.definition.json.DefinitionJsonException
import com.Fusion.Btremix.definition.validator.DefinitionValidationException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedFramingSchemaTest {
    @Test
    fun realPackageDefinitionValidatesSegmentAndMatcherModel() {
        val definition = DefinitionJsonCodec.decode(source())
        val framing = requireNotNull(definition.protocol.framing)

        assertEquals(5, definition.manifest.schemaVersion)
        assertEquals("segmented", framing.codec)
        assertEquals("lengthprefixed", framing.splitter.normalized().function)
        assertEquals(2, framing.segmented?.request?.commandWidthBytes)
        assertEquals(2, framing.segmented?.response?.commandWidthBytes)
        assertEquals(36867, definition.protocol.transactions.getValue("query").matcher?.command)
    }

    @Test
    fun unknownSegmentFieldIsRejectedAtItsPath() {
        val malformed = source().replaceFirst(
            "\"role\": \"magic\",",
            "\"role\": \"magic\", \"mystery\": true,",
        )
        val error = runCatching { DefinitionJsonCodec.decode(malformed) }.exceptionOrNull()

        assertTrue(error is DefinitionJsonException)
        assertEquals(
            "protocol.framing.request[0].mystery",
            (error as DefinitionJsonException).path,
        )
    }

    @Test
    fun legacyFieldsAndUnknownFieldsAreRejectedForSegmentedFraming() {
        val legacyField = source().replace(
            "\"codec\": \"segmented\",",
            "\"codec\": \"segmented\", \"header\": 165,",
        )
        val unknownField = source().replace(
            "\"codec\": \"segmented\",",
            "\"codec\": \"segmented\", \"mystery\": true,",
        )

        assertEquals(
            "protocol.framing.header",
            (runCatching { DefinitionJsonCodec.decode(legacyField) }.exceptionOrNull() as DefinitionJsonException).path,
        )
        assertEquals(
            "protocol.framing.mystery",
            (runCatching { DefinitionJsonCodec.decode(unknownField) }.exceptionOrNull() as DefinitionJsonException).path,
        )
    }

    @Test
    fun commandAndMatcherMustFitTheirDeclaredSegments() {
        val wideRequest = source().replace(
            "\"requestCommand\": 4099",
            "\"requestCommand\": 1193046",
        )
        val commandError = validationFailure(wideRequest)
        assertTrue(
            commandError.errors.any {
                it.path == "protocol.transactions.query.requestCommand" &&
                    it.message.contains("2-byte")
            },
        )

        val unreachableSequence = source().replaceFirst(
            "\"payloadPrefix\": \"02\"",
            "\"payloadPrefix\": \"02\", \"sequence\": true",
        )
        val matcherError = validationFailure(unreachableSequence)
        assertTrue(
            matcherError.errors.any {
                it.path == "protocol.transactions.query.matcher.sequence" &&
                    it.message.contains("no sequence")
            },
        )
    }

    private fun validationFailure(json: String): DefinitionValidationException {
        val error = runCatching { DefinitionJsonCodec.decode(json) }.exceptionOrNull()
        assertTrue("expected validation failure but was $error", error is DefinitionValidationException)
        return error as DefinitionValidationException
    }

    private fun source(): String = CANDIDATES
        .asSequence()
        .map { File(it, "definition.json") }
        .first(File::isFile)
        .readText()

    private companion object {
        val CANDIDATES = listOf(
            File("../../samples/lab.static"),
            File("../samples/lab.static"),
            File("samples/lab.static"),
        )
    }
}

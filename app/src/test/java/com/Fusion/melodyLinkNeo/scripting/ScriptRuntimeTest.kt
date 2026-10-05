package com.fusion.melodyLinkNeo.scripting

import com.fusion.melodyLinkNeo.definition.json.DefinitionJsonCodec
import com.fusion.melodyLinkNeo.device.runtime.ActionResult
import com.fusion.melodyLinkNeo.device.runtime.DeviceAction
import com.fusion.melodyLinkNeo.device.runtime.StateValue
import com.fusion.melodyLinkNeo.scripting.api.ScriptHost
import com.fusion.melodyLinkNeo.scripting.api.DefaultScriptDriver
import com.fusion.melodyLinkNeo.scripting.api.ScriptCodec
import com.fusion.melodyLinkNeo.scripting.api.ScriptFormatException
import com.fusion.melodyLinkNeo.definition.json.JsonParser
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRuntimeTest {
    @Test
    fun definition_parsesScriptAndDriverUsesOnlyGrantedHost() = runBlocking {
        val definition = DefinitionJsonCodec.decode("""
            {"manifest":{"id":"script.demo","displayName":"Script","version":"1","matchers":[{"type":"namePrefix","value":"Demo"}]},
             "states":{"mode":{"type":"string"}},
             "actions":{"set":{"script":[
               {"op":"ble.write","service":"0000180f-0000-1000-8000-00805f9b34fb","characteristic":"00002a19-0000-1000-8000-00805f9b34fb","value":{"hex":"0102"}},
               {"op":"state.set","key":"mode","value":{"arg":"value"}},
               {"op":"return","value":{"state":"mode"}}
             ]}}}
        """.trimIndent())
        val host = FakeHost()
        val result = DefaultScriptDriver(host).execute(
            definition.actions.getValue("set").script!!,
            DeviceAction("set", mapOf("value" to StateValue.StringValue("active"))),
        )

        assertEquals(ActionResult.Success(StateValue.StringValue("active")), result)
        assertTrue(host.writes.single().contentEquals(byteArrayOf(1, 2)))
        assertEquals(StateValue.StringValue("active"), host.states["mode"])
    }

    @Test
    fun driver_rejectsWriteValuesThatAreNotBytes() = runBlocking {
        val host = FakeHost()
        val result = DefaultScriptDriver(host).execute(
            com.fusion.melodyLinkNeo.scripting.api.ScriptProgram(listOf(
                com.fusion.melodyLinkNeo.scripting.api.ScriptStep.Write(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    com.fusion.melodyLinkNeo.scripting.api.ScriptExpression.Literal(StateValue.StringValue("bad")),
                ),
            )),
            DeviceAction("write"),
        )
        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun codec_rejectsUnknownOperationsBeforeExecution() {
        try {
            ScriptCodec.parse(JsonParser.parse("[{\"op\":\"shell.exec\"}]"), "actions.demo.script")
            error("Expected script format failure")
        } catch (error: ScriptFormatException) {
            assertTrue(error.message!!.contains("unsupported operation"))
        }
    }

    private class FakeHost : ScriptHost {
        val states = mutableMapOf<String, StateValue>()
        val writes = mutableListOf<ByteArray>()
        override suspend fun read(service: String, characteristic: String) = byteArrayOf()
        override suspend fun write(service: String, characteristic: String, value: ByteArray, withResponse: Boolean) { writes += value }
        override fun notifications(service: String, characteristic: String): Flow<ByteArray> = flowOf(byteArrayOf())
        override suspend fun getState(key: String): StateValue? = states[key]
        override suspend fun setState(key: String, value: StateValue) { states[key] = value }
        override suspend fun emit(name: String, value: StateValue) = Unit
    }
}

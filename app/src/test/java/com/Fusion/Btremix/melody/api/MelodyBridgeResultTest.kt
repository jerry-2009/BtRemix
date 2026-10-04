package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.device.runtime.RuntimeError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `int` status contract of `IMelodyBridge.execute` (MELODY_BRIDGE_SPEC §8).
 *
 * Both ends share these constants; the tests make sure no `RuntimeError` maps to a nonsense code and
 * that the human-readable names - which is all the panel and logcat ever see - stay stable.
 */
class MelodyBridgeResultTest {

    @Test
    fun runtimeErrors_mapToDistinctBridgeCodes() {
        val cases = mapOf(
            RuntimeError.InvalidState("not ready") to MelodyBridgeResult.ERROR_DEVICE_NOT_READY,
            RuntimeError.ConnectionFailed("gone") to MelodyBridgeResult.ERROR_SESSION_UNAVAILABLE,
            RuntimeError.InitializationFailed("nope") to MelodyBridgeResult.ERROR_SESSION_UNAVAILABLE,
            RuntimeError.ActionNotFound("anc.set") to MelodyBridgeResult.ERROR_ACTION_NOT_FOUND,
            RuntimeError.ActionFailed("anc.set", "rejected") to MelodyBridgeResult.ERROR_ACTION_FAILED,
            RuntimeError.ScriptFailed("anc.set", "timeout") to MelodyBridgeResult.ERROR_ACTION_FAILED,
            RuntimeError.DefinitionBindingFailed("sony", "bad") to MelodyBridgeResult.ERROR_INTERNAL,
        )

        cases.forEach { (error, expected) ->
            assertEquals(error.code, expected, MelodyBridgeResult.from(error))
        }
    }

    @Test
    fun names_areStableAndUnknownCodesDegrade() {
        assertEquals("ok", MelodyBridgeResult.name(MelodyBridgeResult.OK))
        assertEquals("session_unavailable", MelodyBridgeResult.name(MelodyBridgeResult.ERROR_SESSION_UNAVAILABLE))
        assertEquals("action_not_found", MelodyBridgeResult.name(MelodyBridgeResult.ERROR_ACTION_NOT_FOUND))
        assertEquals("action_failed", MelodyBridgeResult.name(MelodyBridgeResult.ERROR_ACTION_FAILED))
        assertEquals("invalid_argument", MelodyBridgeResult.name(MelodyBridgeResult.ERROR_INVALID_ARGUMENT))
        assertEquals("unauthorized", MelodyBridgeResult.name(MelodyBridgeResult.ERROR_UNAUTHORIZED))
        assertEquals("device_not_ready", MelodyBridgeResult.name(MelodyBridgeResult.ERROR_DEVICE_NOT_READY))
        assertEquals("internal", MelodyBridgeResult.name(MelodyBridgeResult.ERROR_INTERNAL))
        assertEquals("error_99", MelodyBridgeResult.name(99))
    }

    @Test
    fun isOk_onlyAcceptsZero() {
        assertTrue(MelodyBridgeResult.isOk(MelodyBridgeResult.OK))
        assertFalse(MelodyBridgeResult.isOk(MelodyBridgeResult.ERROR_INTERNAL))
    }
}

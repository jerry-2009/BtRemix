package com.fusion.melodyLinkNeo.melody.api

import com.fusion.melodyLinkNeo.device.runtime.RuntimeError

/**
 * Status codes returned by `IMelodyBridge.execute` (MELODY_BRIDGE_SPEC §8).
 *
 * AIDL methods return primitives, so the runtime's typed [RuntimeError] is flattened to an `int` here.
 * Keeping the mapping pure means both ends agree on the meaning of a code without shipping the error
 * class hierarchy across the boundary, and the panel can show a localised string while `logcat` keeps
 * the stable `code=N name=...` pair for diagnosis.
 */
object MelodyBridgeResult {

    const val OK: Int = 0

    /** No live session is registered for the MAC (e.g. nothing connected on the BtRemix side yet). */
    const val ERROR_SESSION_UNAVAILABLE: Int = 1

    /** The Definition attached to the session has no action with that id. */
    const val ERROR_ACTION_NOT_FOUND: Int = 2

    /** The action ran but the device rejected it, timed out or threw. */
    const val ERROR_ACTION_FAILED: Int = 3

    /** The caller sent a blank MAC/action id or args that could not be decoded. */
    const val ERROR_INVALID_ARGUMENT: Int = 4

    /** Binder.getCallingUid() is not com.oplus.melody (nor BtRemix itself). */
    const val ERROR_UNAUTHORIZED: Int = 5

    /** The session exists but is not Ready yet (still connecting / initialising). */
    const val ERROR_DEVICE_NOT_READY: Int = 6

    /** Anything the bridge itself failed at; the caller should surface a generic error. */
    const val ERROR_INTERNAL: Int = 7

    fun from(error: RuntimeError): Int = when (error) {
        is RuntimeError.InvalidState -> ERROR_DEVICE_NOT_READY
        is RuntimeError.ConnectionFailed -> ERROR_SESSION_UNAVAILABLE
        is RuntimeError.InitializationFailed -> ERROR_SESSION_UNAVAILABLE
        is RuntimeError.ActionNotFound -> ERROR_ACTION_NOT_FOUND
        is RuntimeError.ActionFailed -> ERROR_ACTION_FAILED
        is RuntimeError.ScriptFailed -> ERROR_ACTION_FAILED
        is RuntimeError.DefinitionBindingFailed -> ERROR_INTERNAL
    }

    /** Stable, logcat-friendly name for a code; unknown values degrade to `error_<n>`. */
    fun name(code: Int): String = when (code) {
        OK -> "ok"
        ERROR_SESSION_UNAVAILABLE -> "session_unavailable"
        ERROR_ACTION_NOT_FOUND -> "action_not_found"
        ERROR_ACTION_FAILED -> "action_failed"
        ERROR_INVALID_ARGUMENT -> "invalid_argument"
        ERROR_UNAUTHORIZED -> "unauthorized"
        ERROR_DEVICE_NOT_READY -> "device_not_ready"
        ERROR_INTERNAL -> "internal"
        else -> "error_$code"
    }

    fun isOk(code: Int): Boolean = code == OK
}

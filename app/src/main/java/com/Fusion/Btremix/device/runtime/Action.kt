package com.Fusion.Btremix.device.runtime

data class DeviceAction(
    val id: String,
    val args: Map<String, StateValue> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "Action id cannot be blank" }
    }
}

fun interface DeviceActionHandler {
    suspend fun handle(action: DeviceAction): ActionResult
}

sealed interface ActionResult {
    data class Success(val value: StateValue? = null) : ActionResult
    data class Failure(val error: RuntimeError) : ActionResult
}

sealed interface RuntimeError {
    val message: String
    val code: String
        get() = this::class.simpleName ?: "runtime_error"
    val recoverable: Boolean
        get() = true
    val cause: Throwable?
        get() = null

    data class InvalidState(override val message: String) : RuntimeError
    data class ConnectionFailed(override val message: String) : RuntimeError
    data class InitializationFailed(override val message: String) : RuntimeError
    data class ActionNotFound(val id: String) : RuntimeError {
        override val message: String = "Action not found: $id"
    }
    data class ActionFailed(val id: String, override val message: String, override val cause: Throwable? = null) : RuntimeError
    data class ScriptFailed(val id: String, override val message: String, override val cause: Throwable? = null) : RuntimeError
    data class DefinitionBindingFailed(
        val definitionId: String,
        override val message: String,
        override val cause: Throwable? = null,
    ) : RuntimeError
}

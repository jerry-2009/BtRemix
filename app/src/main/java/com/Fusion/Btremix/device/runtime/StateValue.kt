package com.Fusion.Btremix.device.runtime

/** Explicit, transport-neutral values stored by a device runtime. */
sealed interface StateValue {
    data class BooleanValue(val value: Boolean) : StateValue
    data class IntValue(val value: Int) : StateValue
    data class LongValue(val value: Long) : StateValue
    data class FloatValue(val value: Float) : StateValue
    data class DoubleValue(val value: Double) : StateValue
    data class StringValue(val value: String) : StateValue
    data class BytesValue(val value: ByteArray) : StateValue {
        override fun equals(other: Any?): Boolean = other is BytesValue && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
    }
    data class ListValue(val value: List<StateValue>) : StateValue
    data class MapValue(val value: Map<String, StateValue>) : StateValue

    companion object {
        fun of(value: Any?): StateValue = when (value) {
            is StateValue -> value
            is Boolean -> BooleanValue(value)
            is Int -> IntValue(value)
            is Long -> LongValue(value)
            is Float -> FloatValue(value)
            is Double -> DoubleValue(value)
            is String -> StringValue(value)
            is ByteArray -> BytesValue(value.clone())
            is List<*> -> ListValue(value.map { of(it) })
            is Map<*, *> -> MapValue(value.entries.associate { (key, item) ->
                require(key is String) { "StateValue map keys must be strings" }
                key to of(item)
            })
            else -> error("Unsupported state value type: ${value?.let { it::class.qualifiedName } ?: "null"}")
        }
    }
}

enum class StateSource {
    INITIAL,
    READ,
    NOTIFICATION,
    ACTION,
    RUNTIME,
    UNKNOWN,
}

enum class StateQuality {
    FRESH,
    STALE,
    UNKNOWN,
}

data class StateEntry(
    val value: StateValue,
    val timestamp: java.time.Instant,
    val source: StateSource = StateSource.RUNTIME,
    val quality: StateQuality = StateQuality.FRESH,
)

data class StateChange(
    val key: String,
    val previous: StateEntry?,
    val entry: StateEntry,
)

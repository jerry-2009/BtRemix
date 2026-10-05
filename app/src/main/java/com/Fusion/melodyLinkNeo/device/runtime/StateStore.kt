package com.fusion.melodyLinkNeo.device.runtime

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

interface StateStore {
    val entries: kotlinx.coroutines.flow.StateFlow<Map<String, StateEntry>>
    val state: kotlinx.coroutines.flow.StateFlow<Map<String, StateEntry>>
        get() = entries
    val changes: Flow<StateChange>

    operator fun get(key: String): StateEntry?
    fun value(key: String): StateValue?
    suspend fun set(
        key: String,
        value: StateValue,
        source: StateSource = StateSource.RUNTIME,
        quality: StateQuality = StateQuality.FRESH,
        timestamp: Instant? = null,
    )
    suspend fun set(
        key: String,
        value: Any,
        source: StateSource = StateSource.RUNTIME,
        quality: StateQuality = StateQuality.FRESH,
        timestamp: Instant? = null,
    ) = set(key, StateValue.of(value), source, quality, timestamp)

    suspend fun update(
        key: String,
        value: StateValue,
        source: StateSource = StateSource.RUNTIME,
        quality: StateQuality = StateQuality.FRESH,
        timestamp: Instant? = null,
    ) = set(key, value, source, quality, timestamp)

    suspend fun update(
        key: String,
        value: Any,
        source: StateSource = StateSource.RUNTIME,
        quality: StateQuality = StateQuality.FRESH,
        timestamp: Instant? = null,
    ) = set(key, StateValue.of(value), source, quality, timestamp)

    suspend fun remove(key: String): StateEntry?
    suspend fun clear()
}

class InMemoryStateStore(private val clock: Clock = Clock.systemUTC()) : StateStore {
    private val values = ConcurrentHashMap<String, StateEntry>()
    private val entriesImpl = MutableStateFlow<Map<String, StateEntry>>(emptyMap())
    private val changesImpl = MutableSharedFlow<StateChange>(extraBufferCapacity = 64)

    override val entries = entriesImpl.asStateFlow()
    override val changes = changesImpl.asSharedFlow()

    override operator fun get(key: String): StateEntry? = values[key]

    override fun value(key: String): StateValue? = values[key]?.value

    override suspend fun set(
        key: String,
        value: StateValue,
        source: StateSource,
        quality: StateQuality,
        timestamp: java.time.Instant?,
    ) {
        require(key.isNotBlank()) { "State key cannot be blank" }
        val entry = StateEntry(value, timestamp ?: clock.instant(), source, quality)
        val previous = values.put(key, entry)
        entriesImpl.value = values.toMap()
        changesImpl.emit(StateChange(key, previous, entry))
    }

    override suspend fun remove(key: String): StateEntry? {
        val previous = values.remove(key) ?: return null
        entriesImpl.value = values.toMap()
        return previous
    }

    override suspend fun clear() {
        values.clear()
        entriesImpl.value = emptyMap()
    }
}

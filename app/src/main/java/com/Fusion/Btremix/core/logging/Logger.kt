package com.Fusion.Btremix.core.logging

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

enum class LogCategory {
    SCAN,
    CONNECTION,
    GATT,
    NOTIFICATION,
    ERROR,
}

data class LogEntry(
    val timestamp: Instant,
    val category: LogCategory,
    val message: String,
    val deviceId: String? = null,
    val uuid: String? = null,
    val data: ByteArray? = null,
)

interface Logger {
    val entries: Flow<List<LogEntry>>
    fun log(entry: LogEntry)
    fun clear()
}

class InMemoryLogger(private val maxEntries: Int = 500) : Logger {
    private val mutableEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    override val entries: Flow<List<LogEntry>> = mutableEntries.asStateFlow()

    override fun log(entry: LogEntry) {
        mutableEntries.update { (it + entry).takeLast(maxEntries.coerceAtLeast(1)) }
    }

    override fun clear() {
        mutableEntries.value = emptyList()
    }
}


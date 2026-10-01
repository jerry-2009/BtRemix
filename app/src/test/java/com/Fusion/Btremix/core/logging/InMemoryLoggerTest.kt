package com.Fusion.Btremix.core.logging

import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryLoggerTest {
    @Test
    fun log_retainsNewestEntriesAndClearEmptiesTheStream() = runBlocking {
        val logger = InMemoryLogger(maxEntries = 2)
        repeat(3) { index ->
            logger.log(LogEntry(Instant.ofEpochSecond(index.toLong()), LogCategory.GATT, "entry $index"))
        }

        assertEquals(listOf("entry 1", "entry 2"), logger.entries.first().map { it.message })
        logger.clear()
        assertEquals(emptyList<LogEntry>(), logger.entries.first())
    }
}

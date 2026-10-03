package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M1 regression coverage for the log-throttling bug found during device acceptance: the host's
 * registry polling produced hundreds of identical lines per second, logd then dropped unrelated
 * diagnostics (the panel row dump never reached logcat). The limiter must keep the first line, then
 * merge duplicates and report how many were merged.
 */
class ObservationRateLimiterTest {

    @Test
    fun firstCallIsLoggedWithoutSuppressedCount() {
        val limiter = ObservationRateLimiter(intervalMs = 10_000)

        assertEquals(0, limiter.allow("h|AA:BB", nowMs = 1_000))
    }

    @Test
    fun duplicatesInsideTheWindowAreMergedIntoTheNextLine() {
        val limiter = ObservationRateLimiter(intervalMs = 10_000)

        assertEquals(0, limiter.allow("h|AA:BB", nowMs = 1_000))
        assertNull(limiter.allow("h|AA:BB", nowMs = 1_500))
        assertNull(limiter.allow("h|AA:BB", nowMs = 9_999))
        assertEquals(2, limiter.allow("h|AA:BB", nowMs = 11_000))
        assertEquals(0, limiter.allow("h|AA:BB", nowMs = 21_000))
    }

    @Test
    fun keysAreThrottledIndependently() {
        val limiter = ObservationRateLimiter(intervalMs = 10_000)

        assertEquals(0, limiter.allow("h|AA:BB", nowMs = 1_000))
        assertEquals(0, limiter.allow("h|CC:DD", nowMs = 1_000))
        assertNull(limiter.allow("h|AA:BB", nowMs = 1_001))
    }

    @Test
    fun disabledKeyIsNeverThrottled() {
        val limiter = ObservationRateLimiter(intervalMs = 10_000)

        assertEquals(0, limiter.allow("create|1", nowMs = 1_000, enabled = false))
        assertEquals(0, limiter.allow("create|1", nowMs = 1_001, enabled = false))
    }
}

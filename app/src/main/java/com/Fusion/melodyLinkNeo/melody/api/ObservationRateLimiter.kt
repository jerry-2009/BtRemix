package com.fusion.melodyLinkNeo.melody.api

/**
 * Rate limiter for repeated identical observations (M1, MELODY_BRIDGE_SPEC §12).
 *
 * The host's device registry is polled once per bonded device on every refresh; on a real device
 * that is hundreds of lines per second from a single log tag, which makes logd drop unrelated
 * diagnostics (observed on the OnePlus PJ16P test device during M1 acceptance). The limiter keeps
 * the interesting signal — the first call, then a periodic line carrying the number of duplicates
 * that were merged into it — while keeping the stream cheap enough for logd to retain everything.
 *
 * Pure Kotlin with an injected clock so the behaviour is pinned down by JVM tests.
 */
class ObservationRateLimiter(private val intervalMs: Long) {

    private class Entry {
        var hasLogged: Boolean = false
        var lastLoggedAt: Long = 0L
        var suppressed: Int = 0
    }

    private val entries = HashMap<String, Entry>()

    /**
     * Decides whether [key] may be logged at [nowMs].
     *
     * @param enabled `false` for events that must always be logged (structural changes such as
     *   creating a device entry).
     * @return the number of duplicates suppressed since the previous log line, or `null` when this
     *   call must stay silent (it has been counted as suppressed instead).
     */
    @Synchronized
    fun allow(key: String, nowMs: Long, enabled: Boolean = true): Int? {
        if (!enabled || intervalMs <= 0L) return 0
        val entry = entries.getOrPut(key) { Entry() }
        if (entry.hasLogged && nowMs - entry.lastLoggedAt < intervalMs) {
            entry.suppressed++
            return null
        }
        val suppressed = entry.suppressed
        entry.suppressed = 0
        entry.hasLogged = true
        entry.lastLoggedAt = nowMs
        return suppressed
    }
}

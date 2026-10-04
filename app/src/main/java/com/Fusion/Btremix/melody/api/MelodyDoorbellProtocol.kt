package com.Fusion.Btremix.melody.api

/**
 * Wire contract of the "doorbell" handshake that carries the [com.Fusion.Btremix.melody.bridge.IMelodyBridge]
 * binder into `com.oplus.melody` (MELODY_BRIDGE_TRANSPORT_PLAN.md §4, MELODY_BRIDGE_SPEC §8.1).
 *
 * The original transport ("Melody binds BtRemix's service") is refused by package visibility on ColorOS, and
 * the reverse direction is refused by OEM signature permissions. Binder transactions themselves are not
 * restricted, so the only problem left is *delivering the binder*: BtRemix puts it into a
 * `setPackage("com.oplus.melody")` broadcast's extras and the injected dynamic receiver in the host process
 * takes it out. Everything after that is an ordinary binder call that the service still UID-checks.
 *
 * This object is plain Kotlin with no Android imports on purpose: the idempotence rule and the delivery
 * cadence are the parts that would otherwise only be observable in logcat, so they are pinned by JVM tests
 * while the Android halves (sender/receiver) stay thin.
 */
object MelodyDoorbellProtocol {

    /** Broadcast action. The host manifest has no entry for it; delivery relies on the dynamic receiver. */
    const val ACTION: String = "com.Fusion.Btremix.melody.BRIDGE_DOORBELL"

    /** Version of the extras layout below; a receiver ignores a doorbell it does not understand. */
    const val VERSION: Int = 1

    const val EXTRA_BRIDGE: String = "bridge"
    const val EXTRA_GENERATION: String = "generation"
    const val EXTRA_PROTOCOL: String = "protocol"
    const val EXTRA_SENDER: String = "sender"

    /** Steady cadence once a client is attached; still needed so a restarted host process gets a binder. */
    const val KEEPALIVE_MS: Long = 30_000L

    /** Burst before the first client attaches: immediately, then 2s/5s/10s and the 30s cap. */
    private val BACKOFF_MS: LongArray = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 30_000L)

    /** `false` for a version this build cannot parse (0 = missing extra, `VERSION + n` = newer sender). */
    fun isCompatible(protocolVersion: Int): Boolean = protocolVersion in 1..VERSION

    /**
     * Idempotence rule (plan §4.2-3): a repeated doorbell for the same generation is dropped while the
     * proxy is still alive, because the client already registered on that binder. Anything else is taken -
     * a new generation means a new binder, and a dead link must be replaced even if the generation repeats
     * (a host process that restarts after BtRemix does can legitimately see the same counter again).
     */
    fun accepts(generation: Int, currentGeneration: Int, hasLiveProxy: Boolean): Boolean =
        !(hasLiveProxy && generation == currentGeneration)

    /**
     * Delay before doorbell number [attempt] (1-based). Attached clients only need the keepalive, so the
     * burst exists purely to catch a host process that has just registered its receiver.
     */
    fun nextDelayMs(attempt: Int, clientAttached: Boolean): Long {
        if (clientAttached) return KEEPALIVE_MS
        val index = (attempt - 1).coerceAtLeast(0).coerceAtMost(BACKOFF_MS.lastIndex)
        return BACKOFF_MS[index]
    }
}

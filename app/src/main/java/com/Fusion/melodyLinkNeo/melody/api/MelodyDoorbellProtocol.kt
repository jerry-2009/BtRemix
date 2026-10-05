package com.fusion.melodyLinkNeo.melody.api

/**
 * Wire contract of the "doorbell" handshake that carries the [com.fusion.melodyLinkNeo.melody.bridge.IMelodyBridge]
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
    const val ACTION: String = "com.fusion.melodyLinkNeo.melody.BRIDGE_DOORBELL"

    /**
     * M5.4b (review follow-up 2026-10-05): the reverse nudge. The injected host process sends this to
     * BtRemix when it starts or when a panel page comes up, and BtRemix answers with one immediate
     * doorbell.
     *
     * It exists because the doorbell is the *only* way the binder reaches the host, so a host that
     * cold-starts between two ticks waits for the next broadcast - up to 30 s once the sender left its
     * burst (measured 6.9 s in the 21:02 capture). The hello carries no data and grants nothing: the
     * worst a spoofed hello can do is make BtRemix ring one extra doorbell, because every binder call
     * still checks `Binder.getCallingUid()` on the BtRemix side.
     */
    const val HELLO_ACTION: String = "com.fusion.melodyLinkNeo.melody.HOST_ALIVE"

    /** Module package the hello is addressed to (`setPackage` = explicit, no discovery involved). */
    const val MODULE_PACKAGE: String = "com.fusion.melodyLinkNeo"

    /** Version of the extras layout below; a receiver ignores a doorbell it does not understand. */
    const val VERSION: Int = 1

    /** Minimum spacing between two hello-triggered doorbells, on both sides of the boundary. */
    const val HELLO_MIN_INTERVAL_MS: Long = 1_000L

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

    /**
     * Host side: send a hello only when there is no link yet (a linked process already has the binder,
     * and its [com.fusion.melodyLinkNeo.melody.bridge.IMelodyBridge.requestDoorbell] covers the sibling
     * process), and never more often than [HELLO_MIN_INTERVAL_MS].
     */
    fun shouldGreet(hasLink: Boolean, elapsedSinceLastHelloMs: Long): Boolean =
        !hasLink && elapsedSinceLastHelloMs >= HELLO_MIN_INTERVAL_MS

    /**
     * BtRemix side: honour a hello only while the bridge service is alive (nothing to offer otherwise,
     * and ringing would only wake the app for nothing) and at the same rate limit.
     */
    fun acceptsHello(serviceAlive: Boolean, elapsedSinceLastRingMs: Long): Boolean =
        serviceAlive && elapsedSinceLastRingMs >= HELLO_MIN_INTERVAL_MS
}

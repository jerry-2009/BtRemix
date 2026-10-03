package com.Fusion.Btremix.device.session

import com.Fusion.Btremix.device.runtime.ProtocolSession
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped owner of live device sessions, keyed by normalised MAC address.
 *
 * MELODY_BRIDGE_SPEC §3.3 makes single-session ownership the most important constraint: the same
 * physical headset may be driven from exactly one place at a time. The registry is that place - the
 * Compose UI and the Melody bridge both look a session up here instead of opening their own
 * connection (wiring lands in M2; M0 ships the skeleton plus the normalisation contract).
 *
 * Lookups are MAC-keyed and case/whitespace insensitive: Android hands out addresses in several
 * shapes across `BluetoothDevice`, `BleScanResult` and provider extras, and a mismatch would
 * silently allow a second session.
 */
class SessionRegistry {

    private val sessions = ConcurrentHashMap<String, ProtocolSession>()

    /** The session currently owning [mac], or `null` when the MAC is not managed. */
    fun find(mac: String): ProtocolSession? = sessions[normalize(mac)]

    /** Normalised MACs that currently have a registered session, in a stable order. */
    fun managedMacs(): List<String> = sessions.keys.sorted()

    /** Registers [session] for [mac], returning the previous owner when one was replaced. */
    fun register(mac: String, session: ProtocolSession): ProtocolSession? = sessions.put(normalize(mac), session)

    /** Detaches the session for [mac] without closing it. */
    fun remove(mac: String): ProtocolSession? = sessions.remove(normalize(mac))

    /** Detaches and closes the session for [mac]. Safe when the MAC is unknown. */
    suspend fun close(mac: String) {
        remove(mac)?.close()
    }

    /** Detaches and closes every registered session; used when the owning service shuts down. */
    suspend fun closeAll() {
        while (true) {
            val mac = sessions.keys.firstOrNull() ?: return
            sessions.remove(mac)?.close()
        }
    }

    companion object {
        /** Canonical form used for every registry key: trimmed and upper-cased with a fixed locale. */
        fun normalize(mac: String): String = mac.trim().uppercase(Locale.ROOT)
    }
}

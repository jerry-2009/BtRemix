package com.Fusion.Btremix.melody.hook.bridge

import android.content.Context
import com.Fusion.Btremix.melody.api.MelodyProjectionStore
import com.Fusion.Btremix.melody.projection.MelodyProjectionBuilder

/**
 * Android persistence adapter for [MelodyProjectionStore] (MELODY_BRIDGE_SPEC §5.5, M3.3 plan).
 *
 * The store lives in the host process' own `SharedPreferences`, which is exactly the lifetime the
 * cold-start requirement needs: it survives BtRemix being killed and the host being restarted, so the
 * provider can still answer with the last known projections instead of "this device is unsupported".
 *
 * All I/O is best-effort — a failed read/write must never surface in the host process.
 */
internal class MelodyProjectionPreferences(
    context: Context,
    private val store: MelodyProjectionStore,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Loads the persisted document; `false` when it is absent, corrupt or written by another version. */
    fun load(): Boolean = runCatching {
        store.load(prefs.getString(KEY_PAYLOAD, null), MelodyProjectionBuilder.ENVELOPE_VERSION)
    }.getOrDefault(false)

    /** `true` when text was persisted before, which distinguishes "empty" from "version mismatch". */
    fun hasPersisted(): Boolean =
        runCatching { !prefs.getString(KEY_PAYLOAD, null).isNullOrEmpty() }.getOrDefault(false)

    fun save() {
        runCatching { prefs.edit().putString(KEY_PAYLOAD, store.toJson()).apply() }
    }

    fun clear() {
        runCatching { prefs.edit().remove(KEY_PAYLOAD).apply() }
    }

    private companion object {
        const val PREFS_NAME = "melody_projection_cache"
        const val KEY_PAYLOAD = "projections"
    }
}

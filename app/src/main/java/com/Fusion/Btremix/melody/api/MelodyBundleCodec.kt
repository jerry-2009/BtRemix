package com.Fusion.Btremix.melody.api

import android.os.Bundle
import com.Fusion.Btremix.device.runtime.StateValue
import com.Fusion.Btremix.device.session.SessionSnapshot

/**
 * Android half of the bridge payload mapping (MELODY_BRIDGE_SPEC §8): [WireValue] <-> `Bundle`, and
 * the runtime snapshot <-> [MelodySnapshot] adapters.
 *
 * The tree is encoded as `Bundle { t: <tag>, v: <payload> }`; nested lists/maps hold further bundles,
 * which is what keeps the format self-describing without registering a custom Parcelable per value
 * type. The pure logic lives in [StateValueCodec]; this file only knows how to put those primitives
 * into a `Bundle`, so the untestable-on-JVM surface stays as small as possible.
 */
object MelodyBundleCodec {

    private const val KEY_TAG = "t"
    private const val KEY_VALUE = "v"

    private const val TAG_BOOL = "b"
    private const val TAG_INT = "i"
    private const val TAG_LONG = "l"
    private const val TAG_FLOAT = "f"
    private const val TAG_DOUBLE = "d"
    private const val TAG_TEXT = "s"
    private const val TAG_BYTES = "y"
    private const val TAG_LIST = "a"
    private const val TAG_MAP = "m"

    // --- value tree ---------------------------------------------------------------------------

    fun encode(value: WireValue): Bundle = Bundle().apply {
        when (value) {
            is WireValue.Bool -> {
                putString(KEY_TAG, TAG_BOOL); putBoolean(KEY_VALUE, value.value)
            }
            is WireValue.Int32 -> {
                putString(KEY_TAG, TAG_INT); putInt(KEY_VALUE, value.value)
            }
            is WireValue.Int64 -> {
                putString(KEY_TAG, TAG_LONG); putLong(KEY_VALUE, value.value)
            }
            is WireValue.Float32 -> {
                putString(KEY_TAG, TAG_FLOAT); putFloat(KEY_VALUE, value.value)
            }
            is WireValue.Float64 -> {
                putString(KEY_TAG, TAG_DOUBLE); putDouble(KEY_VALUE, value.value)
            }
            is WireValue.Text -> {
                putString(KEY_TAG, TAG_TEXT); putString(KEY_VALUE, value.value)
            }
            is WireValue.Bytes -> {
                putString(KEY_TAG, TAG_BYTES); putByteArray(KEY_VALUE, value.value)
            }
            is WireValue.Items -> {
                putString(KEY_TAG, TAG_LIST)
                putParcelableArrayList(KEY_VALUE, ArrayList(value.value.map(::encode)))
            }
            is WireValue.Fields -> {
                putString(KEY_TAG, TAG_MAP)
                putBundle(
                    KEY_VALUE,
                    Bundle().apply { value.value.forEach { (key, item) -> putBundle(key, encode(item)) } },
                )
            }
        }
    }

    fun decode(bundle: Bundle?): WireValue {
        if (bundle == null) return WireValue.Text("")
        return when (bundle.getString(KEY_TAG)) {
            TAG_BOOL -> WireValue.Bool(bundle.getBoolean(KEY_VALUE))
            TAG_INT -> WireValue.Int32(bundle.getInt(KEY_VALUE))
            TAG_LONG -> WireValue.Int64(bundle.getLong(KEY_VALUE))
            TAG_FLOAT -> WireValue.Float32(bundle.getFloat(KEY_VALUE))
            TAG_DOUBLE -> WireValue.Float64(bundle.getDouble(KEY_VALUE))
            TAG_TEXT -> WireValue.Text(bundle.getString(KEY_VALUE).orEmpty())
            TAG_BYTES -> WireValue.Bytes(bundle.getByteArray(KEY_VALUE) ?: ByteArray(0))
            TAG_LIST -> WireValue.Items(
                bundle.getParcelableArrayList(KEY_VALUE, Bundle::class.java)
                    ?.map { decode(it) }
                    .orEmpty(),
            )
            TAG_MAP -> WireValue.Fields(
                bundle.getBundle(KEY_VALUE)?.let { map ->
                    map.keySet().associateWith { key -> decode(map.getBundle(key)) }
                }.orEmpty(),
            )
            else -> WireValue.Text("")
        }
    }

    // --- state entries ------------------------------------------------------------------------

    private const val KEY_ENTRY_VALUE = "value"
    private const val KEY_ENTRY_TIMESTAMP = "ts"
    private const val KEY_ENTRY_SOURCE = "src"
    private const val KEY_ENTRY_QUALITY = "q"

    fun encodeEntry(entry: WireEntry): Bundle = Bundle().apply {
        putBundle(KEY_ENTRY_VALUE, encode(entry.value))
        putLong(KEY_ENTRY_TIMESTAMP, entry.timestampMillis)
        putString(KEY_ENTRY_SOURCE, entry.source.name)
        putString(KEY_ENTRY_QUALITY, entry.quality.name)
    }

    fun decodeEntry(bundle: Bundle?): WireEntry {
        val safe = bundle ?: Bundle()
        return WireEntry(
            value = decode(safe.getBundle(KEY_ENTRY_VALUE)),
            timestampMillis = safe.getLong(KEY_ENTRY_TIMESTAMP),
            source = safe.getString(KEY_ENTRY_SOURCE)
                ?.let { name -> com.Fusion.Btremix.device.runtime.StateSource.entries.firstOrNull { it.name == name } }
                ?: com.Fusion.Btremix.device.runtime.StateSource.RUNTIME,
            quality = safe.getString(KEY_ENTRY_QUALITY)
                ?.let { name -> com.Fusion.Btremix.device.runtime.StateQuality.entries.firstOrNull { it.name == name } }
                ?: com.Fusion.Btremix.device.runtime.StateQuality.FRESH,
        )
    }

    /** Encodes a `key -> StateEntry` map (the snapshot body). */
    fun encodeState(entries: Map<String, com.Fusion.Btremix.device.runtime.StateEntry>): Bundle =
        Bundle().apply {
            entries.toWireEntries().forEach { (key, entry) -> putBundle(key, encodeEntry(entry)) }
        }

    /** Decodes a snapshot body back into runtime state entries. */
    fun decodeState(bundle: Bundle?): Map<String, com.Fusion.Btremix.device.runtime.StateEntry> =
        bundle?.let { body ->
            body.keySet().associateWith { key -> decodeEntry(body.getBundle(key)).toStateEntry() }
        }.orEmpty()

    // --- action args --------------------------------------------------------------------------

    fun encodeArgs(args: Map<String, StateValue>): Bundle = Bundle().apply {
        args.forEach { (key, value) -> putBundle(key, encode(value.toWire())) }
    }

    fun decodeArgs(bundle: Bundle?): Map<String, StateValue> = bundle?.let { body ->
        body.keySet().associateWith { key -> decode(body.getBundle(key)).toStateValue() }
    }.orEmpty()

    // --- snapshot -----------------------------------------------------------------------------

    fun encodeSnapshot(snapshot: SessionSnapshot): MelodySnapshot = MelodySnapshot(
        mac = snapshot.mac,
        lifecycle = MelodyLifecycleWire.nameOf(snapshot.lifecycle),
        errorMessage = MelodyLifecycleWire.messageOf(snapshot.lifecycle),
        state = encodeState(snapshot.state),
    )

    /** Best-effort reverse of [encodeSnapshot]; an error state loses its concrete [RuntimeError] type. */
    fun decodeSnapshot(snapshot: MelodySnapshot): SessionSnapshot = SessionSnapshot(
        mac = snapshot.mac,
        lifecycle = MelodyLifecycleWire.stateOf(snapshot.lifecycle, snapshot.errorMessage),
        state = decodeState(snapshot.state),
    )
}

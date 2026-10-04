package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.device.runtime.StateEntry
import com.Fusion.Btremix.device.runtime.StateQuality
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.device.runtime.StateValue
import java.time.Instant

/**
 * Pure-Kotlin half of the cross-process value mapping (MELODY_BRIDGE_SPEC §8).
 *
 * AIDL can only carry a fixed set of primitives, so [StateValue] is projected onto [WireValue] - a
 * closed, serialisation-shaped tree - before the Bundle adapter in [MelodyBundleCodec] turns it into
 * something `android.os.Bundle` understands. Splitting the two keeps the interesting logic (the type
 * dispatch, the deep copies, the nesting) free of Android imports, which is what makes the round trip
 * testable on the JVM, exactly like the protocol codecs.
 */
sealed interface WireValue {
    data class Bool(val value: Boolean) : WireValue
    data class Int32(val value: Int) : WireValue
    data class Int64(val value: Long) : WireValue
    data class Float32(val value: Float) : WireValue
    data class Float64(val value: Double) : WireValue
    data class Text(val value: String) : WireValue
    data class Bytes(val value: ByteArray) : WireValue {
        override fun equals(other: Any?): Boolean = other is Bytes && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
    }
    data class Items(val value: List<WireValue>) : WireValue
    data class Fields(val value: Map<String, WireValue>) : WireValue
}

/** Serialisation-shaped [StateEntry]: value tree plus the metadata the panel needs for staleness. */
data class WireEntry(
    val value: WireValue,
    val timestampMillis: Long,
    val source: StateSource,
    val quality: StateQuality,
)

fun StateValue.toWire(): WireValue = when (this) {
    is StateValue.BooleanValue -> WireValue.Bool(value)
    is StateValue.IntValue -> WireValue.Int32(value)
    is StateValue.LongValue -> WireValue.Int64(value)
    is StateValue.FloatValue -> WireValue.Float32(value)
    is StateValue.DoubleValue -> WireValue.Float64(value)
    is StateValue.StringValue -> WireValue.Text(value)
    is StateValue.BytesValue -> WireValue.Bytes(value.clone())
    is StateValue.ListValue -> WireValue.Items(value.map { it.toWire() })
    is StateValue.MapValue -> WireValue.Fields(value.mapValues { (_, item) -> item.toWire() })
}

fun WireValue.toStateValue(): StateValue = when (this) {
    is WireValue.Bool -> StateValue.BooleanValue(value)
    is WireValue.Int32 -> StateValue.IntValue(value)
    is WireValue.Int64 -> StateValue.LongValue(value)
    is WireValue.Float32 -> StateValue.FloatValue(value)
    is WireValue.Float64 -> StateValue.DoubleValue(value)
    is WireValue.Text -> StateValue.StringValue(value)
    is WireValue.Bytes -> StateValue.BytesValue(value.clone())
    is WireValue.Items -> StateValue.ListValue(value.map { it.toStateValue() })
    is WireValue.Fields -> StateValue.MapValue(value.mapValues { (_, item) -> item.toStateValue() })
}

fun StateEntry.toWireEntry(): WireEntry = WireEntry(
    value = value.toWire(),
    timestampMillis = timestamp.toEpochMilli(),
    source = source,
    quality = quality,
)

fun WireEntry.toStateEntry(): StateEntry = StateEntry(
    value = value.toStateValue(),
    timestamp = Instant.ofEpochMilli(timestampMillis),
    source = source,
    quality = quality,
)

fun Map<String, StateEntry>.toWireEntries(): Map<String, WireEntry> =
    mapValues { (_, entry) -> entry.toWireEntry() }

fun Map<String, WireEntry>.toStateEntries(): Map<String, StateEntry> =
    mapValues { (_, entry) -> entry.toStateEntry() }

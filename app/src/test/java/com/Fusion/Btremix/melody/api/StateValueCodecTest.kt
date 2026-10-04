package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.device.runtime.StateEntry
import com.Fusion.Btremix.device.runtime.StateQuality
import com.Fusion.Btremix.device.runtime.StateSource
import com.Fusion.Btremix.device.runtime.StateValue
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round trips the value tree that crosses the process boundary (MELODY_BRIDGE_SPEC §8, §13.1).
 *
 * The AIDL layer can only carry primitives, so every [StateValue] shape has to survive
 * `StateValue -> WireValue -> StateValue` unchanged; a missed shape would only show up as a wrong value
 * on a real headset, which is exactly the kind of bug the JVM suite is here to catch first.
 */
class StateValueCodecTest {

    @Test
    fun scalarValues_roundTrip() {
        val values = listOf(
            StateValue.BooleanValue(true),
            StateValue.IntValue(-3),
            StateValue.LongValue(9_000_000_000L),
            StateValue.FloatValue(1.5f),
            StateValue.DoubleValue(2.25),
            StateValue.StringValue("transparency"),
        )

        values.forEach { value -> assertEquals(value, value.toWire().toStateValue()) }
    }

    @Test
    fun bytesValue_roundTripsByContent() {
        val value = StateValue.BytesValue(byteArrayOf(1, 2, 3))
        assertEquals(value, value.toWire().toStateValue())
    }

    @Test
    fun toWire_copiesBytesSoLaterMutationDoesNotLeak() {
        val source = byteArrayOf(1, 2, 3)
        val wire = StateValue.BytesValue(source).toWire()

        source[0] = 9

        assertTrue(wire is WireValue.Bytes)
        assertEquals(1.toByte(), (wire as WireValue.Bytes).value[0])
    }

    @Test
    fun toStateValue_copiesBytesSoTheWireTreeStaysIndependent() {
        val wire = WireValue.Bytes(byteArrayOf(4, 5, 6))
        val decoded = wire.toStateValue()

        assertTrue(decoded is StateValue.BytesValue)
        assertNotSame(wire.value, (decoded as StateValue.BytesValue).value)
    }

    @Test
    fun nestedListAndMap_roundTrip() {
        val value = StateValue.ListValue(
            listOf(
                StateValue.IntValue(1),
                StateValue.MapValue(
                    mapOf(
                        "mode" to StateValue.StringValue("anc"),
                        "levels" to StateValue.ListValue(
                            listOf(StateValue.DoubleValue(0.5), StateValue.BooleanValue(false)),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(value, value.toWire().toStateValue())
    }

    @Test
    fun stateEntry_roundTripsMetadata() {
        val entry = StateEntry(
            value = StateValue.IntValue(83),
            timestamp = Instant.ofEpochMilli(1_700_000_000_123L),
            source = StateSource.NOTIFICATION,
            quality = StateQuality.STALE,
        )

        val decoded = entry.toWireEntry().toStateEntry()

        assertEquals(entry, decoded)
        assertEquals(StateSource.NOTIFICATION, decoded.source)
        assertEquals(StateQuality.STALE, decoded.quality)
    }

    @Test
    fun stateMap_roundTripsAsASet() {
        val entries = mapOf(
            "battery.left" to StateEntry(StateValue.IntValue(83), Instant.ofEpochMilli(1)),
            "anc.mode" to StateEntry(StateValue.StringValue("transparency"), Instant.ofEpochMilli(2)),
        )

        val decoded = entries.toWireEntries().toStateEntries()

        assertEquals(entries, decoded)
    }
}

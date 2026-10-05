package com.fusion.melodyLinkNeo.melody.api

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fusion.melodyLinkNeo.device.runtime.StateQuality
import com.fusion.melodyLinkNeo.device.runtime.StateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M4.3a regression: reading the battery out of an encoded session snapshot.
 *
 * `MelodySnapshot.state` maps a state **name** to the *entry* bundle (`{value, ts, src, q}`). The first
 * M4.3a implementation decoded the entry bundle as if it were the value bundle, so every level read back
 * as `Text("")` and the Melody header rendered "connected" with an empty battery area. This test goes
 * through the real codec, which is where that mistake lived.
 *
 * It runs on a device because the wire format is `android.os.Bundle`; the JVM half of the mapping
 * (`MelodyEarphoneBattery.ofStates`) is covered by `MelodyEarphoneProjectionTest`.
 */
@RunWith(AndroidJUnit4::class)
class MelodyEarphoneBatterySnapshotTest {

    @Test
    fun snapshotBody_decodesTheBatteryLevelsThroughTheEntryBundle() {
        val snapshot = snapshotOf(
            "battery.left" to WireValue.Int32(80),
            "battery.right" to WireValue.Int32(90),
            "battery.case" to WireValue.Int32(55),
            "ancMode" to WireValue.Text("anc"),
        )

        val battery = MelodyEarphoneBattery.ofSnapshot(snapshot)

        assertEquals(80, battery?.left)
        assertEquals(90, battery?.right)
        assertEquals(55, battery?.box)
    }

    @Test
    fun aPortionOfTheLevelsIsStillUsable() {
        val snapshot = snapshotOf("battery.left" to WireValue.Int32(42))

        val battery = MelodyEarphoneBattery.ofSnapshot(snapshot)

        assertEquals(42, battery?.left)
        assertNull(battery?.right)
        assertNull(battery?.box)
    }

    @Test
    fun aSnapshotWithoutBatteryStatesProjectsNothing() {
        assertNull(MelodyEarphoneBattery.ofSnapshot(snapshotOf("ancMode" to WireValue.Text("anc"))))
        assertNull(
            MelodyEarphoneBattery.ofSnapshot(
                MelodySnapshot("14:3F:A6:02:5F:B0", MelodyLifecycleWire.DISCONNECTED, null, Bundle()),
            ),
        )
    }

    private fun snapshotOf(vararg states: Pair<String, WireValue>): MelodySnapshot {
        val body = Bundle()
        states.forEach { (key, value) ->
            body.putBundle(
                key,
                MelodyBundleCodec.encodeEntry(
                    WireEntry(
                        value = value,
                        timestampMillis = 0L,
                        source = StateSource.RUNTIME,
                        quality = StateQuality.FRESH,
                    ),
                ),
            )
        }
        return MelodySnapshot(
            mac = "14:3F:A6:02:5F:B0",
            lifecycle = MelodyLifecycleWire.READY,
            errorMessage = null,
            state = body,
        )
    }
}

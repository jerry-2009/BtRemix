package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M5.3 acceptance for the `melody_method_*` payload parser (`HANDOFF_MELODY_M5_PLAN.md` §4 M5.3 /
 * `docs/melody-capability-map.md` §8.6). Only `melody_method_noise_reduction` with an `address` and a
 * `type` is ours; every other method and every incomplete payload fails open to `chain.proceed()`.
 */
class MelodyProviderCallPolicyTest {

    @Test
    fun aNoiseReductionCall_readsTheMacAndTheHostModeType() {
        assertEquals(
            MelodyProviderCallPolicy.Command(mac = MAC, modeType = 5),
            MelodyProviderCallPolicy.parse(
                MelodyProviderCallPolicy.METHOD_NOISE_REDUCTION,
                extras(
                    "name" to "WH-1000XM3",
                    "address" to MAC,
                    "type" to 5,
                    "switch" to 1,
                ),
            ),
        )
    }

    @Test
    fun aChildStrengthModeType_decodesToo() {
        // `type` is a `modeType`, and the provider lane also reaches the child「降噪效果」positions
        // (3 Low / 8 Moderate / 4 High), so the parser must not filter them out.
        assertEquals(
            MelodyProviderCallPolicy.Command(mac = MAC, modeType = 3),
            MelodyProviderCallPolicy.parse(
                MelodyProviderCallPolicy.METHOD_NOISE_REDUCTION,
                extras("address" to MAC, "type" to 3),
            ),
        )
    }

    @Test
    fun theOtherMelodyMethods_areNeverOurs() {
        assertNull(
            MelodyProviderCallPolicy.parse(
                "melody_method_spatial",
                extras("address" to MAC, "type" to 5),
            ),
        )
        assertNull(
            MelodyProviderCallPolicy.parse(
                "melody_method_active_device",
                extras("address" to MAC, "type" to 5),
            ),
        )
        assertNull(
            MelodyProviderCallPolicy.parse(
                "melody_method_control_mode",
                extras("address" to MAC, "type" to 5),
            ),
        )
        assertNull(MelodyProviderCallPolicy.parse(null, extras("address" to MAC, "type" to 5)))
    }

    @Test
    fun aMissingOrBlankAddress_failsOpen() {
        assertNull(MelodyProviderCallPolicy.parse(MelodyProviderCallPolicy.METHOD_NOISE_REDUCTION, extras("type" to 5)))
        assertNull(
            MelodyProviderCallPolicy.parse(
                MelodyProviderCallPolicy.METHOD_NOISE_REDUCTION,
                extras("address" to "   ", "type" to 5),
            ),
        )
    }

    @Test
    fun aMissingTypeOrMissingExtras_failsOpen() {
        assertNull(
            MelodyProviderCallPolicy.parse(
                MelodyProviderCallPolicy.METHOD_NOISE_REDUCTION,
                extras("address" to MAC),
            ),
        )
        assertNull(MelodyProviderCallPolicy.parse(MelodyProviderCallPolicy.METHOD_NOISE_REDUCTION, null))
    }

    @Test
    fun theDecodeDoesNotFilterValues_theMappingDoes() {
        // A `type` the injected table does not know is still a decoded command; `MelodyAncRedirectPolicy`
        // is what turns it into `Skip(unmapped)`. Keeping that split is what makes both halves testable.
        assertEquals(
            MelodyProviderCallPolicy.Command(mac = MAC, modeType = 99),
            MelodyProviderCallPolicy.parse(
                MelodyProviderCallPolicy.METHOD_NOISE_REDUCTION,
                extras("address" to MAC, "type" to 99),
            ),
        )
    }

    private fun extras(vararg values: Pair<String, Any>): MelodyProviderCallPolicy.Extras =
        object : MelodyProviderCallPolicy.Extras {
            override fun string(key: String): String? =
                values.firstOrNull { it.first == key }?.second as? String

            override fun int(key: String): Int? =
                values.firstOrNull { it.first == key }?.second as? Int
        }

    private companion object {
        const val MAC = "14:3F:A6:02:5F:B0"
    }
}

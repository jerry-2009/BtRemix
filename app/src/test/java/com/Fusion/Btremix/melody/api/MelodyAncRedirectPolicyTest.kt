package com.Fusion.Btremix.melody.api

import com.Fusion.Btremix.definition.api.MelodyAncMode
import com.Fusion.Btremix.definition.api.MelodyAncStrengthDefinition
import com.Fusion.Btremix.definition.api.MelodyAncStrengthLevel
import com.Fusion.Btremix.device.runtime.StateValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5.1 acceptance for the pure redirect mapping (`HANDOFF_MELODY_M5_PLAN.md` §4 M5.1, decision D-18):
 * the parent mode table and the strength child table are the only mapping truth, everything else fails
 * open, and an already-projected index is a noop instead of a redundant write.
 */
class MelodyAncRedirectPolicyTest {

    @Test
    fun parentIndex_mapsToTheDefinitionsModeActionAndArgumentName() {
        val decision = decide(index = 0)

        assertEquals(
            MelodyAncRedirectPolicy.Decision.Redirect(
                actionId = "anc.setMode",
                args = mapOf("mode" to StateValue.StringValue("anc")),
            ),
            decision,
        )
    }

    @Test
    fun childIndex_mapsToTheStrengthActionAndLevelArgument() {
        val decision = decide(index = 11)

        assertEquals(
            MelodyAncRedirectPolicy.Decision.Redirect(
                actionId = "anc.setLevel",
                args = mapOf("value" to StateValue.IntValue(10)),
            ),
            decision,
        )
    }

    @Test
    fun anIndexInNeitherTable_isUnmapped() {
        assertSkip(MelodyAncRedirectPolicy.REASON_UNMAPPED, decide(index = 99))
    }

    @Test
    fun anIndexEqualToTheProjectedOne_isANoop() {
        assertSkip(MelodyAncRedirectPolicy.REASON_NOOP, decide(index = 0, currentIndex = 0))
    }

    @Test
    fun anUnmanagedMac_neverConsultsTheEnvelope() {
        assertSkip(MelodyAncRedirectPolicy.REASON_NOT_MANAGED, decide(index = 0, managed = false))
        assertSkip(MelodyAncRedirectPolicy.REASON_NOT_MANAGED, decide(index = 0, managed = false, anc = null))
    }

    @Test
    fun aMissingEnvelope_isReportedSeparatelyFromAMissingAncNode() {
        assertSkip(MelodyAncRedirectPolicy.REASON_NO_ENVELOPE, decide(index = 0, anc = null))
        assertSkip(MelodyAncRedirectPolicy.REASON_NO_ANC, decide(index = 0, anc = MelodyAncPolicy.NONE))
        assertSkip(
            MelodyAncRedirectPolicy.REASON_NO_ANC,
            decide(index = 0, anc = MelodyAncPolicy(uiVersion = 1, modes = emptyList(), strength = null)),
        )
    }

    @Test
    fun aChildIndexWithoutAStrengthTable_isUnmapped() {
        assertSkip(
            MelodyAncRedirectPolicy.REASON_UNMAPPED,
            decide(index = 11, anc = policy(strength = null)),
        )
    }

    @Test
    fun aMissingModeActionOrArgumentName_failsOpenInsteadOfGuessing() {
        val missingAction = policy(modeAction = null)
        assertSkip(MelodyAncRedirectPolicy.REASON_UNMAPPED, decide(index = 0, anc = missingAction))

        val missingParam = policy(modeParam = null)
        assertSkip(MelodyAncRedirectPolicy.REASON_UNMAPPED, decide(index = 0, anc = missingParam))
    }

    @Test
    fun aMissingStrengthArgumentName_failsOpen() {
        assertSkip(
            MelodyAncRedirectPolicy.REASON_UNMAPPED,
            decide(index = 11, anc = policy(strengthParam = null)),
        )
    }

    // --- M5.2: the `modeType` keyed mapping --------------------------------------------------------

    @Test
    fun modeType_mapsToTheSameParentRedirectAsItsProtocolIndex() {
        assertEquals(
            MelodyAncRedirectPolicy.Decision.Redirect(
                actionId = "anc.setMode",
                args = mapOf("mode" to StateValue.StringValue("anc")),
            ),
            decideByModeType(modeType = 5),
        )
        assertEquals(
            MelodyAncRedirectPolicy.Decision.Redirect(
                actionId = "anc.setMode",
                args = mapOf("mode" to StateValue.StringValue("off")),
            ),
            decideByModeType(modeType = 1),
        )
    }

    @Test
    fun aChildModeType_isNotReachableThroughTheParentTable() {
        // 3 / 8 are the strength child `modeType`s, nested under the noise-cancelling entry; the
        // `setgate` host mapping (`NoiseReductionCommand.a`) only searches the top-level table, so
        // neither may be guessed through this key. (The provider lane has its own function below,
        // because `EarphoneControlProvider`'s `L.n` resolver *does* search the children.)
        assertSkip(MelodyAncRedirectPolicy.REASON_UNMAPPED, decideByModeType(modeType = 3))
        assertSkip(MelodyAncRedirectPolicy.REASON_UNMAPPED, decideByModeType(modeType = 8))
    }

    @Test
    fun anUnknownModeType_isUnmapped() {
        assertSkip(MelodyAncRedirectPolicy.REASON_UNMAPPED, decideByModeType(modeType = 99))
    }

    @Test
    fun modeType_equalToTheProjectedIndex_isANoop() {
        assertSkip(MelodyAncRedirectPolicy.REASON_NOOP, decideByModeType(modeType = 5, currentIndex = 0))
    }

    @Test
    fun modeType_keepsTheNotManagedAndNoEnvelopeOrdering() {
        assertSkip(MelodyAncRedirectPolicy.REASON_NOT_MANAGED, decideByModeType(modeType = 5, managed = false))
        assertSkip(MelodyAncRedirectPolicy.REASON_NOT_MANAGED, decideByModeType(modeType = 5, managed = false, anc = null))
        assertSkip(MelodyAncRedirectPolicy.REASON_NO_ENVELOPE, decideByModeType(modeType = 5, anc = null))
        assertSkip(MelodyAncRedirectPolicy.REASON_NO_ANC, decideByModeType(modeType = 5, anc = MelodyAncPolicy.NONE))
    }

    // --- M5.3: the device-centre SDK lane (a `modeType` through parents *and* children) -------------

    @Test
    fun providerModeType_reachesAChildStrengthLevel() {
        assertEquals(
            MelodyAncRedirectPolicy.Decision.Redirect(
                actionId = "anc.setLevel",
                args = mapOf("value" to StateValue.IntValue(1)),
            ),
            decideByProviderModeType(modeType = 3),
        )
    }

    @Test
    fun providerModeType_keepsTheParentTableFirst() {
        assertEquals(
            MelodyAncRedirectPolicy.Decision.Redirect(
                actionId = "anc.setMode",
                args = mapOf("mode" to StateValue.StringValue("anc")),
            ),
            decideByProviderModeType(modeType = 5),
        )
    }

    @Test
    fun providerModeType_unlistedOrUnknown_isUnmapped() {
        // 7 (Auto) is a host child `modeType` this package does not declare; 99 is nothing at all.
        assertSkip(MelodyAncRedirectPolicy.REASON_UNMAPPED, decideByProviderModeType(modeType = 7))
        assertSkip(MelodyAncRedirectPolicy.REASON_UNMAPPED, decideByProviderModeType(modeType = 99))
    }

    @Test
    fun providerModeType_alreadyProjectedChild_isANoop() {
        assertSkip(MelodyAncRedirectPolicy.REASON_NOOP, decideByProviderModeType(modeType = 3, currentIndex = 10))
    }

    @Test
    fun providerModeType_keepsTheFailOpenOrdering() {
        assertSkip(MelodyAncRedirectPolicy.REASON_NOT_MANAGED, decideByProviderModeType(modeType = 3, managed = false))
        assertSkip(
            MelodyAncRedirectPolicy.REASON_NOT_MANAGED,
            decideByProviderModeType(modeType = 3, managed = false, anc = null),
        )
        assertSkip(MelodyAncRedirectPolicy.REASON_NO_ENVELOPE, decideByProviderModeType(modeType = 3, anc = null))
        assertSkip(MelodyAncRedirectPolicy.REASON_NO_ANC, decideByProviderModeType(modeType = 3, anc = MelodyAncPolicy.NONE))
        assertSkip(
            MelodyAncRedirectPolicy.REASON_UNMAPPED,
            decideByProviderModeType(modeType = 3, anc = policy(strength = null)),
        )
    }

    @Test
    fun indexOfModeType_searchesChildrenOnlyWhenAsked() {
        assertEquals(0, MelodyAncRedirectPolicy.indexOfModeType(5, policy(), includeChildren = true))
        assertEquals(0, MelodyAncRedirectPolicy.indexOfModeType(5, policy(), includeChildren = false))
        assertEquals(10, MelodyAncRedirectPolicy.indexOfModeType(3, policy(), includeChildren = true))
        assertNull(MelodyAncRedirectPolicy.indexOfModeType(3, policy(), includeChildren = false))
        assertNull(MelodyAncRedirectPolicy.indexOfModeType(3, null, includeChildren = true))
    }

    // --- drivers ----------------------------------------------------------------------------------

    private fun decide(
        index: Int,
        managed: Boolean = true,
        anc: MelodyAncPolicy? = policy(),
        currentIndex: Int? = null,
    ): MelodyAncRedirectPolicy.Decision =
        MelodyAncRedirectPolicy.decide(MAC, index, managed, anc, currentIndex)

    private fun decideByModeType(
        modeType: Int,
        managed: Boolean = true,
        anc: MelodyAncPolicy? = policy(),
        currentIndex: Int? = null,
    ): MelodyAncRedirectPolicy.Decision =
        MelodyAncRedirectPolicy.decideByModeType(MAC, modeType, managed, anc, currentIndex)

    private fun decideByProviderModeType(
        modeType: Int,
        managed: Boolean = true,
        anc: MelodyAncPolicy? = policy(),
        currentIndex: Int? = null,
    ): MelodyAncRedirectPolicy.Decision =
        MelodyAncRedirectPolicy.decideByModeTypeIncludingChildren(MAC, modeType, managed, anc, currentIndex)

    private fun policy(
        modeAction: String? = "anc.setMode",
        modeParam: String? = "mode",
        strengthParam: String? = "value",
        strength: MelodyAncStrengthDefinition? = STRENGTH,
    ): MelodyAncPolicy = MelodyAncPolicy(
        uiVersion = 1,
        modes = MODES,
        strength = strength,
        modeAction = modeAction,
        modeParam = modeParam,
        strengthParam = strengthParam,
    )

    private fun assertSkip(expected: String, decision: MelodyAncRedirectPolicy.Decision) {
        assertTrue("expected Skip($expected) but was $decision", decision == MelodyAncRedirectPolicy.Decision.Skip(expected))
    }

    private companion object {
        const val MAC = "14:3F:A6:02:5F:B0"

        val MODES = listOf(
            MelodyAncMode(modeType = 5, protocolIndex = 0, state = "anc"),
            MelodyAncMode(modeType = 1, protocolIndex = 1, state = "off"),
        )

        val STRENGTH = MelodyAncStrengthDefinition(
            state = "ancLevel",
            action = "anc.setLevel",
            levels = listOf(
                MelodyAncStrengthLevel(modeType = 3, protocolIndex = 10, level = 1),
                MelodyAncStrengthLevel(modeType = 8, protocolIndex = 11, level = 10),
                MelodyAncStrengthLevel(modeType = 4, protocolIndex = 12, level = 20),
            ),
        )
    }
}

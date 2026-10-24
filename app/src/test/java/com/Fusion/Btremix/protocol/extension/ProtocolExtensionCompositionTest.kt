package com.Fusion.Btremix.protocol.extension

import com.Fusion.Btremix.firstPartyProtocolExtensions
import com.Fusion.Btremix.vendor.testbed.ReferenceChallengeExtension
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolExtensionCompositionTest {
    @Test
    fun applicationCompositionRoot_registersTheReferenceExtensionExplicitly() {
        val registered = firstPartyProtocolExtensions().all()

        assertEquals(listOf(ReferenceChallengeExtension.ID), registered.map { it.id })
        assertEquals(setOf(ReferenceChallengeExtension.ENTRY), registered.single().entries)
    }
}

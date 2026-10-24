package com.Fusion.Btremix.protocol.extension

import com.Fusion.Btremix.firstPartyProtocolExtensions
import com.Fusion.Btremix.vendor.testbed.ReferenceChallengeExtension
import com.Fusion.Btremix.vendor.xiaomi.XiaomiMmaAuthExtension
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolExtensionCompositionTest {
    @Test
    fun applicationCompositionRoot_registersFirstPartyExtensionsExplicitly() {
        val registered = firstPartyProtocolExtensions().all()

        assertEquals(
            listOf(ReferenceChallengeExtension.ID, XiaomiMmaAuthExtension.ID),
            registered.map { it.id },
        )
        assertEquals(
            setOf(ReferenceChallengeExtension.ENTRY),
            registered.first { it.id == ReferenceChallengeExtension.ID }.entries,
        )
        assertEquals(
            setOf(XiaomiMmaAuthExtension.ENTRY),
            registered.first { it.id == XiaomiMmaAuthExtension.ID }.entries,
        )
    }
}

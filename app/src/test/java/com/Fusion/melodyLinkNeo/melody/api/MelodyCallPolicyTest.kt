package com.fusion.melodyLinkNeo.melody.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The security boundary of the exported bridge service (MELODY_BRIDGE_SPEC §8).
 *
 * The service carries no `android:permission`, so this decision function - keyed on the un-forgeable
 * calling UID and the packages it owns - is the only thing standing between the bridge and any other
 * app that knows the component name.
 */
class MelodyCallPolicyTest {

    private val selfUid = 10_123
    private val melodyUid = 10_456
    private val otherUid = 10_789

    @Test
    fun hostPackage_isAllowed() {
        assertTrue(
            MelodyCallPolicy.isAuthorized(
                callerPackages = listOf(MelodyCallPolicy.HOST_PACKAGE),
                callerUid = melodyUid,
                selfUid = selfUid,
            ),
        )
    }

    @Test
    fun selfUid_isAllowedEvenWithoutAPackageList() {
        assertTrue(
            MelodyCallPolicy.isAuthorized(
                callerPackages = emptyList(),
                callerUid = selfUid,
                selfUid = selfUid,
            ),
        )
    }

    @Test
    fun sharedUidWithExtraPackages_isStillAllowed() {
        assertTrue(
            MelodyCallPolicy.isAuthorized(
                callerPackages = listOf("com.oplus.melody.heytapmius", MelodyCallPolicy.HOST_PACKAGE),
                callerUid = melodyUid,
                selfUid = selfUid,
            ),
        )
    }

    @Test
    fun foreignPackages_areRejected() {
        assertFalse(
            MelodyCallPolicy.isAuthorized(
                callerPackages = listOf("com.example.malware"),
                callerUid = otherUid,
                selfUid = selfUid,
            ),
        )
    }

    @Test
    fun emptyPackageListFromAForeignUid_isRejected() {
        assertFalse(
            MelodyCallPolicy.isAuthorized(
                callerPackages = emptyList(),
                callerUid = otherUid,
                selfUid = selfUid,
            ),
        )
    }

    @Test
    fun resolvedHostUid_isAllowedEvenIfThePackageListIsFiltered() {
        assertTrue(
            MelodyCallPolicy.isAuthorized(
                callerPackages = emptyList(),
                callerUid = melodyUid,
                selfUid = selfUid,
                hostUid = melodyUid,
            ),
        )
    }

    @Test
    fun aStaleHostUidDoesNotWidenThePolicy() {
        assertFalse(
            MelodyCallPolicy.isAuthorized(
                callerPackages = emptyList(),
                callerUid = otherUid,
                selfUid = selfUid,
                hostUid = melodyUid,
            ),
        )
    }
}

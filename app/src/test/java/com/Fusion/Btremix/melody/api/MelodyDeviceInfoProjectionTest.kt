package com.Fusion.Btremix.melody.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.4 acceptance for the pure part of the `DeviceInfo` synthesis (MELODY_BRIDGE_SPEC §5.2): which
 * fields the host is given, how the session lifecycle becomes profile states, and what the envelope
 * contributes that the official `WhitelistConfigDTO` shape cannot carry.
 */
class MelodyDeviceInfoProjectionTest {

    private val identity = MelodyWhitelistIdentity(
        mac = "14:3f:a6:02:5f:b0",
        name = "Sony WF-1000XM3",
        hexId = "000CE0",
        decimalId = "3296",
        type = "T1",
        uuid = "96cc203e-5068-46ad-b32d-e316f5e069ba",
        supportSpp = false,
        whitelistJson = """{"id":"000CE0"}""",
    )

    private val policy = MelodyDevicePolicy(productType = 1, suppressTransport = true)

    @Test
    fun identity_keepsTheDecimalIdAndNormalisesTheAddress() {
        val result = requireNotNull(MelodyDeviceInfoIdentity.of(identity, policy))

        assertEquals("14:3F:A6:02:5F:B0", result.mac)
        assertEquals("Sony WF-1000XM3", result.name)
        assertEquals(3296, result.productId)
        assertEquals(1, result.productType)
        assertFalse(result.supportSpp)
        assertTrue(result.suppressTransport)
    }

    @Test
    fun identity_rejectsAProductIdTheHostIntFieldCannotHold() {
        assertNull(MelodyDeviceInfoIdentity.of(identity.copy(decimalId = ""), policy))
        assertNull(MelodyDeviceInfoIdentity.of(identity.copy(decimalId = "4294967296"), policy))
    }

    @Test
    fun projection_mapsTheSessionLifecycleOntoProfileStates() {
        val ready = MelodyDeviceInfoProjection.from(
            requireNotNull(MelodyDeviceInfoIdentity.of(identity, policy)),
            MelodyLifecycleWire.READY,
        )

        assertEquals(2, ready.aclState)
        assertEquals(2, ready.a2dpState)
        assertEquals(2, ready.headsetState)
        assertTrue(ready.connected)

        val connecting = MelodyDeviceInfoProjection.from(
            requireNotNull(MelodyDeviceInfoIdentity.of(identity, policy)),
            MelodyLifecycleWire.CONNECTED,
        )
        assertEquals(1, connecting.aclState)
        assertFalse(connecting.connected)
    }

    @Test
    fun projection_alwaysReportsNoSppChannel() {
        val projection = MelodyDeviceInfoProjection.from(
            requireNotNull(MelodyDeviceInfoIdentity.of(identity, policy)),
            MelodyLifecycleWire.READY,
        )

        assertEquals(MelodyDeviceInfoProjection.STATE_DISCONNECTED, projection.sppState)
        assertFalse(projection.supportSpp)
    }

    @Test
    fun profileState_coversEveryLifecycleName() {
        assertEquals(MelodyDeviceInfoProjection.STATE_CONNECTED, MelodyDeviceInfoProjection.profileStateOf(MelodyLifecycleWire.READY))
        assertEquals(MelodyDeviceInfoProjection.STATE_CONNECTED, MelodyDeviceInfoProjection.profileStateOf(MelodyLifecycleWire.REFRESHING_STATE))
        assertEquals(MelodyDeviceInfoProjection.STATE_CONNECTING, MelodyDeviceInfoProjection.profileStateOf(MelodyLifecycleWire.CONNECTING))
        assertEquals(MelodyDeviceInfoProjection.STATE_CONNECTING, MelodyDeviceInfoProjection.profileStateOf(MelodyLifecycleWire.INITIALIZING))
        assertEquals(MelodyDeviceInfoProjection.STATE_DISCONNECTING, MelodyDeviceInfoProjection.profileStateOf(MelodyLifecycleWire.DISCONNECTING))
        assertEquals(MelodyDeviceInfoProjection.STATE_DISCONNECTED, MelodyDeviceInfoProjection.profileStateOf(MelodyLifecycleWire.DISCONNECTED))
        assertEquals(MelodyDeviceInfoProjection.STATE_DISCONNECTED, MelodyDeviceInfoProjection.profileStateOf(MelodyLifecycleWire.ERROR))
        assertEquals(MelodyDeviceInfoProjection.STATE_DISCONNECTED, MelodyDeviceInfoProjection.profileStateOf(null))
    }

    @Test
    fun policy_readsOurOwnDefinitionNodeAndFallsBackToTheSafeDefault() {
        val envelope =
            """{"version":1,"definition":{"id":"sony.wf1000xm3","productType":2,"suppressTransport":false}}"""

        val parsed = MelodyProviderMerge.policyOf(envelope)
        assertEquals(2, parsed.productType)
        assertFalse(parsed.suppressTransport)

        // An envelope from before M3.4 (or a truncated one) must still suppress transport.
        assertEquals(MelodyDevicePolicy.NEUTRAL, MelodyProviderMerge.policyOf("""{"version":1,"definition":{"id":"x"}}"""))
        assertEquals(MelodyDevicePolicy.NEUTRAL, MelodyProviderMerge.policyOf(null))
        assertEquals(MelodyDevicePolicy.NEUTRAL, MelodyProviderMerge.policyOf("{ not json"))
    }

    @Test
    fun projection_takesTheIdentityFromAnEnvelopeTheBuilderWrote() {
        val envelope = """
            {
              "version": 1,
              "mac": "14:3F:A6:02:5F:B0",
              "definition": { "id": "sony.wf1000xm3", "version": "1.0.0", "productType": 1, "suppressTransport": true },
              "whitelist": {
                "id": "000CE0", "name": "Sony WF-1000XM3", "brand": "Sony", "type": "T1",
                "uuid": "96cc203e-5068-46ad-b32d-e316f5e069ba", "supportSpp": false,
                "function": { "batteryRadix": 10 }
              },
              "panel": {}
            }
        """.trimIndent()

        val parsed = requireNotNull(MelodyProviderMerge.identityOf(envelope))
        val result = requireNotNull(
            MelodyDeviceInfoIdentity.of(parsed, MelodyProviderMerge.policyOf(envelope)),
        )

        assertEquals(3296, result.productId)
        assertTrue(result.suppressTransport)
        assertNotNull(result.mac)
    }
}

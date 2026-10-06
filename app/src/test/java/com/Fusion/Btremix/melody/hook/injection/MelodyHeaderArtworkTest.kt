package com.Fusion.Btremix.melody.hook.injection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M7 header artwork: the two decisions the detail / OneSpace hooks make about the host's own picture.
 *
 * `DetailMainViewModel` (`DetailBaseViewModel`) and `OneSpaceVM` keep the page's MAC in an obfuscated
 * `String` field (`e` / `c` in 17.6.3). The stand-ins below carry those very field names next to the
 * other strings the real classes hold, so "a rename that keeps the shape" and "the address is gone" are
 * both covered by the JVM suite without a device.
 */
class MelodyHeaderArtworkTest {

    @Test
    fun detailViewModel_addressComesFromTheMacShapedField() {
        val viewModel = DetailStandIn(
            name = "WF-1000XM3",
            productId = "000CE0",
            deviceModel = "Sony",
            address = "14:3f:a6:02:5f:b0",
        )

        assertEquals("14:3F:A6:02:5F:B0", MelodyHeaderArtwork.macOf(viewModel))
    }

    @Test
    fun oneSpaceViewModel_addressIsNormalised() {
        val viewModel = OneSpaceStandIn(
            address = "AC:12:2F:88:00:0A",
            title = "WF-1000XM3",
            extra = "one_space",
        )

        assertEquals("AC:12:2F:88:00:0A", MelodyHeaderArtwork.macOf(viewModel))
    }

    @Test
    fun aViewModelWithoutAnAddressPaintsNothing() {
        assertNull(MelodyHeaderArtwork.macOf(DetailStandIn(name = "WF-1000XM3", address = null)))
        assertNull(MelodyHeaderArtwork.macOf(OneSpaceStandIn(address = null)))
    }

    @Test
    fun aMissingViewModelPaintsNothing() {
        assertNull(MelodyHeaderArtwork.macOf(null))
    }

    @Test
    fun aNonAddressStringIsNotMistakenForTheDevice() {
        // A decimal product id and a UUID-ish name must not read as an address.
        val viewModel = OneSpaceStandIn(address = null, title = "000CE0", extra = "96cc203e-5068-46ad")

        assertNull(MelodyHeaderArtwork.macOf(viewModel))
    }

    @Test
    fun aDegenerateIconMustNotBlankTheHeader() {
        // Some bundled packages ship a 1x1 marker; painting it would replace a good placeholder with a
        // solid square, so only a real picture may be painted.
        assertFalse(MelodyHeaderArtwork.isUsableIcon(1, 1))
        assertFalse(MelodyHeaderArtwork.isUsableIcon(MelodyHeaderArtwork.MIN_ICON_PX - 1, 720))
        assertTrue(MelodyHeaderArtwork.isUsableIcon(MelodyHeaderArtwork.MIN_ICON_PX, MelodyHeaderArtwork.MIN_ICON_PX))
        assertTrue(MelodyHeaderArtwork.isUsableIcon(720, 720))
    }

    @Test
    fun stopLoading_cancelsAndHidesTheAnimation() {
        val loading = LottieStandIn()

        assertTrue(MelodyHeaderArtwork.stopLoading(loading))
        assertTrue("the animation must be cancelled", loading.cancelled)
        assertEquals(8, loading.visibilityValue)
    }

    @Test
    fun stopLoading_isFailOpen() {
        // No animation (the placeholder branch, or a host without Lottie) must be a no-op, and a view
        // that does not answer `setVisibility` must not be reported as hidden.
        assertFalse(MelodyHeaderArtwork.stopLoading(null))
        assertFalse(MelodyHeaderArtwork.stopLoading(PlainStandIn()))
    }

    // --- stand-ins shaped like the host view-models -----------------------------------------------

    /** Mirrors `DetailBaseViewModel`: several strings, one of which is the page address. */
    @Suppress("unused")
    private class DetailStandIn(
        val name: String?,
        val productId: String? = null,
        val deviceModel: String? = null,
        val address: String? = null,
    )

    /** Mirrors `OneSpaceVM`: `c` is the address, `d` / `e` are other labels. */
    @Suppress("unused")
    private class OneSpaceStandIn(
        val address: String?,
        val title: String? = null,
        val extra: String? = null,
    )

    /** Mirrors the `LottieAnimationView` surface [MelodyHeaderArtwork] drives reflectively. */
    private class LottieStandIn {
        var cancelled = false
        var visibilityValue = 0

        fun cancelAnimation() {
            cancelled = true
        }

        fun setVisibility(value: Int) {
            visibilityValue = value
        }
    }

    /** A host view without the animation API. */
    private class PlainStandIn
}

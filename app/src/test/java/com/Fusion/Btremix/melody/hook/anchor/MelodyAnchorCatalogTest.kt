package com.Fusion.Btremix.melody.hook.anchor

import com.Fusion.Btremix.melody.api.MelodyAnchorBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catalog invariants: unique ids, sane packages, baseline names inside the allowed host prefixes. */
class MelodyAnchorCatalogTest {

    @Test
    fun idsAreUnique() {
        val ids = MelodyAnchorCatalog.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun baselineClassesAreInsideTheHostPackages() {
        for (spec in MelodyAnchorCatalog.all) {
            assertTrue("${spec.id} has no baseline", spec.baselineClasses.isNotEmpty())
            for (name in spec.baselineClasses) {
                assertTrue(
                    "${spec.id} baseline $name must be an Oplus/COUI class",
                    name.startsWith("com.oplus.") || name.startsWith("com.coui.") ||
                        name.substringBeforeLast('.').substringBefore('.') in setOf("A9", "D0", "E2", "Ba", "c7", "d7", "c9", "i9", "n7"),
                )
            }
        }
    }

    @Test
    fun everyAnchorHasAPackageFilterAndALabel() {
        for (spec in MelodyAnchorCatalog.all) {
            assertTrue("${spec.id} has no DexKit packages", spec.packages.isNotEmpty())
            assertFalse("${spec.id} label fell back to the id", MelodyAnchorCatalog.labelOf(spec.id) == spec.id)
        }
    }

    @Test
    fun methodAnchorsUseASignatureQuery() {
        for (spec in MelodyAnchorCatalog.all.filter { it.methodAnchor }) {
            assertTrue(
                "${spec.id} is a method anchor but its query is ${spec.query}",
                spec.query is MelodyAnchorQuery.MethodSignature,
            )
        }
    }

    @Test
    fun redirectV0AllowsMultipleImplementations() {
        assertTrue(requireNotNull(MelodyAnchorCatalog.spec(MelodyAnchorCatalog.REDIRECT_V0)).allowMultiple)
    }

    @Test
    fun hostPackagesMatchTheBroadcastTrustList() {
        for (host in MelodyAnchorHost.entries) {
            assertTrue(MelodyAnchorBroadcast.TRUSTED_HOSTS.contains(host.hostPackage))
        }
    }

    @Test
    fun catalogCoversEveryHookFamily() {
        val ids = MelodyAnchorCatalog.all.map { it.id }.toSet()
        for (id in listOf(
            MelodyAnchorCatalog.TRANSPORT_CLIENT,
            MelodyAnchorCatalog.TRANSPORT_WRITE,
            MelodyAnchorCatalog.REDIRECT_V0,
            MelodyAnchorCatalog.REDIRECT_SETGATE,
            MelodyAnchorCatalog.REDIRECT_PROVIDER,
            MelodyAnchorCatalog.PANEL_GROUP_OBSERVER,
            MelodyAnchorCatalog.PANEL_DEVICE_CONTROL_WIDGET,
            MelodyAnchorCatalog.BTSDK_DEVICE_INFO_MANAGER,
            MelodyAnchorCatalog.BTSDK_DEVICE_INFO,
            MelodyAnchorCatalog.BTSDK_NOISE_INFO,
            MelodyAnchorCatalog.DTO_EARPHONE,
            MelodyAnchorCatalog.PROVIDER_ALIVE,
            MelodyAnchorCatalog.PROVIDER_MY_DEVICE,
            MelodyAnchorCatalog.WHITELIST_REPO_MAPPER,
            MelodyAnchorCatalog.WHITELIST_REPO_IMPL,
            MelodyAnchorCatalog.WHITELIST_UTILS,
            MelodyAnchorCatalog.CARD_SENDER,
            MelodyAnchorCatalog.CARD_VO,
            MelodyAnchorCatalog.CARD_MENU_BUILDER,
            MelodyAnchorCatalog.ANC_REFRESH_ITEM,
            MelodyAnchorCatalog.ANC_REFRESH_ONE_SPACE,
            MelodyAnchorCatalog.ANC_REFRESH_VO,
            MelodyAnchorCatalog.EARPHONE_REPOSITORY,
            MelodyAnchorCatalog.DETAIL_IMAGE_PLACEHOLDER,
            MelodyAnchorCatalog.ONESPACE_IMAGE_PLACEHOLDER,
            MelodyAnchorCatalog.DETAIL_HEADER_BIND,
            MelodyAnchorCatalog.SETTINGS_PODS_DATA_MANAGER,
        )) {
            assertTrue("catalog is missing $id", ids.contains(id))
            assertNotNull(MelodyAnchorCatalog.spec(id))
        }
    }

    /**
     * Regression: these seven anchors reported "missing" after the first real deployment because they
     * were matched only by a recorded (obfuscated) package. They must now carry a shape-based query.
     */
    @Test
    fun previouslyFailingAnchorsHaveShapeMatchers() {
        for (id in listOf(
            MelodyAnchorCatalog.TRANSPORT_CLIENT,
            MelodyAnchorCatalog.TRANSPORT_WRITE,
            MelodyAnchorCatalog.WHITELIST_REPO_MAPPER,
            MelodyAnchorCatalog.PANEL_GROUP_OBSERVER,
            MelodyAnchorCatalog.CARD_MENU_BUILDER,
            MelodyAnchorCatalog.ANC_REFRESH_VO,
            MelodyAnchorCatalog.CARD_SENDER,
        )) {
            val query = requireNotNull(MelodyAnchorCatalog.spec(id)).query
            assertFalse("$id must not be BaselineOnly", query is MelodyAnchorQuery.BaselineOnly)
        }
    }
}

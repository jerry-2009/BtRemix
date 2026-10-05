package com.Fusion.Btremix.melody.hook.anchor

import com.Fusion.Btremix.melody.api.MelodyAnchorEntry
import com.Fusion.Btremix.melody.api.MelodyAnchorLevel
import com.Fusion.Btremix.melody.hook.MelodyAnchorSession
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6 resolution policy: baseline first, then DexKit; ambiguity/forbidden packages are misses; a cached
 * name for the same install skips DexKit entirely. Exercised with fixture classes and a fake lookup, so
 * no device or native library is involved.
 */
class MelodyAnchorSessionTest {

    private class FakeLookup(private val answers: Map<String, List<String>>) : DexAnchorLookup {
        var calls = 0
        override fun findClasses(spec: MelodyAnchorSpec, resolveType: (MelodyTypeRef) -> Class<*>?): List<String> {
            calls += 1
            return answers[spec.id].orEmpty()
        }

        override fun close() = Unit
    }

    @After
    fun tearDown() {
        MelodyAnchorSession.reset()
    }

    private fun spec(
        id: String,
        baselines: List<String>,
        methodName: String? = null,
        methodAnchor: Boolean = false,
        allowMultiple: Boolean = false,
    ) = MelodyAnchorSpec(
        id = id,
        host = MelodyAnchorHost.Melody,
        feature = id,
        baselineClasses = baselines,
        packages = listOf("com.oplus.test"),
        query = MelodyAnchorQuery.MethodSignature(
            methodName,
            listOf(MelodyTypeRef.Of(UUID::class.java)),
            MelodyTypeRef.Of(Void.TYPE),
        ),
        methodAnchor = methodAnchor,
        allowMultiple = allowMultiple,
    )

    @Test
    fun baselineHit_resolvesWithoutDexKit() {
        val lookup = FakeLookup(emptyMap())
        MelodyAnchorSession.beginForTest(
            specs = listOf(spec("a", listOf("com.oplus.test.AlphaTarget"))),
            loader = javaClass.classLoader,
            lookup = lookup,
        )

        val resolved = MelodyAnchorSession.classOrNull("a")

        assertEquals("com.oplus.test.AlphaTarget", resolved?.name)
        assertEquals(0, lookup.calls)
        val report = requireNotNull(MelodyAnchorSession.finish())
        assertEquals(MelodyAnchorLevel.Baseline, report.anchors.single().level)
    }

    @Test
    fun missingBaseline_dexKitUnique_isDexkit() {
        val lookup = FakeLookup(mapOf("a" to listOf("com.oplus.test.AlphaTarget")))
        MelodyAnchorSession.beginForTest(
            specs = listOf(spec("a", listOf("com.oplus.gone.Nope"))),
            loader = javaClass.classLoader,
            lookup = lookup,
        )

        val resolved = MelodyAnchorSession.classOrNull("a")

        assertEquals("com.oplus.test.AlphaTarget", resolved?.name)
        assertEquals(1, lookup.calls)
        assertEquals(MelodyAnchorLevel.Dexkit, requireNotNull(MelodyAnchorSession.finish()).anchors.single().level)
    }

    @Test
    fun ambiguousDexKitAnswer_isMiss() {
        val lookup = FakeLookup(
            mapOf("a" to listOf("com.oplus.test.AlphaTarget", "com.oplus.test.BetaTarget")),
        )
        MelodyAnchorSession.beginForTest(
            specs = listOf(spec("a", listOf("com.oplus.gone.Nope"))),
            loader = javaClass.classLoader,
            lookup = lookup,
        )

        assertTrue(MelodyAnchorSession.classes("a").isEmpty())
        val report = requireNotNull(MelodyAnchorSession.finish())
        assertEquals(MelodyAnchorLevel.Missing, report.anchors.single().level)
        assertTrue(report.anchors.single().missing)
    }

    @Test
    fun dexKitAnswerOutsideAllowedPackages_isMiss() {
        val lookup = FakeLookup(mapOf("a" to listOf("androidx.fixtures.PlatformTarget")))
        MelodyAnchorSession.beginForTest(
            specs = listOf(spec("a", listOf("com.oplus.gone.Nope"))),
            loader = javaClass.classLoader,
            lookup = lookup,
        )

        assertTrue(MelodyAnchorSession.classes("a").isEmpty())
        assertEquals(MelodyAnchorLevel.Missing, requireNotNull(MelodyAnchorSession.finish()).anchors.single().level)
    }

    @Test
    fun abstractDexKitAnswer_isMiss() {
        val lookup = FakeLookup(mapOf("a" to listOf("com.oplus.test.AbstractTarget")))
        MelodyAnchorSession.beginForTest(
            specs = listOf(spec("a", listOf("com.oplus.gone.Nope"))),
            loader = javaClass.classLoader,
            lookup = lookup,
        )

        assertTrue(MelodyAnchorSession.classes("a").isEmpty())
    }

    @Test
    fun cachedNameForSameInstall_isReusedWithoutDexKit() {
        val lookup = FakeLookup(emptyMap())
        MelodyAnchorSession.beginForTest(
            specs = listOf(spec("a", listOf("com.oplus.gone.Nope"))),
            loader = javaClass.classLoader,
            lookup = lookup,
            cached = mapOf(
                "a" to MelodyAnchorEntry("a", "com.oplus.test.AlphaTarget", null, MelodyAnchorLevel.Dexkit),
            ),
        )

        assertEquals("com.oplus.test.AlphaTarget", MelodyAnchorSession.classOrNull("a")?.name)
        assertEquals(0, lookup.calls)
        assertEquals(MelodyAnchorLevel.Cache, requireNotNull(MelodyAnchorSession.finish()).anchors.single().level)
    }

    @Test
    fun cachedNameThatNoLongerLoads_fallsBackToDexKit() {
        val lookup = FakeLookup(mapOf("a" to listOf("com.oplus.test.AlphaTarget")))
        MelodyAnchorSession.beginForTest(
            specs = listOf(spec("a", listOf("com.oplus.gone.Nope"))),
            loader = javaClass.classLoader,
            lookup = lookup,
            cached = mapOf(
                "a" to MelodyAnchorEntry("a", "com.oplus.gone.Stale", null, MelodyAnchorLevel.Dexkit),
            ),
        )

        assertEquals("com.oplus.test.AlphaTarget", MelodyAnchorSession.classOrNull("a")?.name)
        assertEquals(1, lookup.calls)
    }

    @Test
    fun renamedMethod_isReportedAsRename() {
        val lookup = FakeLookup(emptyMap())
        MelodyAnchorSession.beginForTest(
            specs = listOf(
                spec("a", listOf("com.oplus.test.RenamedTarget"), methodName = "probe", methodAnchor = true),
            ),
            loader = javaClass.classLoader,
            lookup = lookup,
        )

        val anchor = MelodyAnchorSession.anchor("a")

        assertEquals("renamedProbe", anchor?.method?.name)
        assertEquals(MelodyAnchorLevel.Rename, requireNotNull(MelodyAnchorSession.finish()).anchors.single().level)
    }

    @Test
    fun allowMultiple_keepsEveryDexKitCandidate() {
        val lookup = FakeLookup(
            mapOf("a" to listOf("com.oplus.test.AlphaTarget", "com.oplus.test.BetaTarget")),
        )
        MelodyAnchorSession.beginForTest(
            specs = listOf(spec("a", listOf("com.oplus.gone.Nope"), allowMultiple = true)),
            loader = javaClass.classLoader,
            lookup = lookup,
        )

        assertEquals(2, MelodyAnchorSession.classes("a").size)
        assertFalse(requireNotNull(MelodyAnchorSession.finish()).anchors.isEmpty())
    }

    @Test
    fun unknownAnchorId_resolvesToNothing() {
        MelodyAnchorSession.beginForTest(emptyList(), javaClass.classLoader, FakeLookup(emptyMap()))
        assertNull(MelodyAnchorSession.classOrNull("not.in.catalog"))
    }

    /**
     * Regression: the module's own baselines live in R8 short packages (`c7.b`, `d7.a`, `D0.d`, `A9.f`,
     * `i9.c`, `Ba.z`, `n7.e$a`), which the first M6 cut rejected with its `com.oplus.` allow list.
     */
    @Test
    fun shortPackageBaseline_isAccepted() {
        MelodyAnchorSession.beginForTest(
            specs = listOf(spec("a", listOf("c7.b"), methodName = "o", methodAnchor = true)),
            loader = javaClass.classLoader,
            lookup = FakeLookup(emptyMap()),
        )

        assertEquals("o", MelodyAnchorSession.anchor("a")?.method?.name)
        assertEquals(MelodyAnchorLevel.Baseline, requireNotNull(MelodyAnchorSession.finish()).anchors.single().level)
    }

    /** Same for a class name cached from a previous install (DexKit answer echoed back in the report). */
    @Test
    fun shortPackageCachedName_isAccepted() {
        val lookup = FakeLookup(emptyMap())
        MelodyAnchorSession.beginForTest(
            specs = listOf(spec("a", listOf("gone.Nope"), methodName = "o", methodAnchor = true)),
            loader = javaClass.classLoader,
            lookup = lookup,
            cached = mapOf("a" to MelodyAnchorEntry("a", "c7.b", "o", MelodyAnchorLevel.Dexkit)),
        )

        assertEquals("o", MelodyAnchorSession.anchor("a")?.method?.name)
        assertEquals(0, lookup.calls)
        assertEquals(MelodyAnchorLevel.Cache, requireNotNull(MelodyAnchorSession.finish()).anchors.single().level)
    }

    /** A `Named` type reference resolves the host class from the loader at scan time. */
    @Test
    fun namedTypeRef_isResolvedFromTheLoader() {
        val lookup = object : DexAnchorLookup {
            var resolvedName: String? = null
            override fun findClasses(spec: MelodyAnchorSpec, resolveType: (MelodyTypeRef) -> Class<*>?): List<String> {
                resolvedName = resolveType(MelodyTypeRef.Named("com.oplus.test.AlphaTarget"))?.name
                return listOf("com.oplus.test.AlphaTarget")
            }

            override fun close() = Unit
        }
        MelodyAnchorSession.beginForTest(
            specs = listOf(spec("a", listOf("gone.Nope"))),
            loader = javaClass.classLoader,
            lookup = lookup,
        )

        assertEquals("com.oplus.test.AlphaTarget", MelodyAnchorSession.classOrNull("a")?.name)
        assertEquals("com.oplus.test.AlphaTarget", lookup.resolvedName)
    }
}

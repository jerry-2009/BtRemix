package com.Fusion.Btremix.melody.hook

import com.Fusion.Btremix.melody.api.MelodyAnchorBroadcast
import com.Fusion.Btremix.melody.api.MelodyAnchorEntry
import com.Fusion.Btremix.melody.api.MelodyAnchorLevel
import com.Fusion.Btremix.melody.api.MelodyAnchorProcessReport
import com.Fusion.Btremix.melody.hook.anchor.DexAnchorLookup
import com.Fusion.Btremix.melody.hook.anchor.DexKitAnchorLookup
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorCatalog
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorHost
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorQuery
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorSpec
import com.Fusion.Btremix.melody.hook.anchor.MelodyMethodShape
import com.Fusion.Btremix.melody.hook.anchor.MelodyTypeRef
import com.Fusion.Btremix.melody.hook.anchor.NoDexAnchorLookup
import java.lang.reflect.Method

/**
 * Process-scoped host anchor resolution (M6, `HANDOFF_MELODY_M6_PLAN.md` §1/§2, revised after the first
 * real host update).
 *
 * One session per injected host process. [begin] is called once from `MelodyBridgeEntry` right after the
 * install fingerprint is known; every hook then resolves its class through [classes] / [classOrNull] (or
 * its method through [anchor] / [anchors]).
 *
 * Resolution order per anchor:
 *
 *  1. the persisted report written for the *same install fingerprint* (a previous process already paid
 *     for DexKit, so reuse the name - still verified by actually loading it);
 *  2. the recorded 17.6.3 baseline names (steady state: no DexKit cost at all);
 *  3. one global DexKit scan (one shared bridge for the whole pass).
 *
 * Baseline names are trusted as recorded (they are our own constants, and R8's short packages like `c7`
 * or `Ba` are not covered by any allow list). DexKit answers and broadcast-cached names go through the
 * platform-package denylist. An ambiguous answer is a miss unless the anchor explicitly allows several;
 * a miss is what the update prompt shows the user.
 */
internal object MelodyAnchorSession {

    /** A resolved class + the method to hook (method anchors only). */
    data class Anchor(val clazz: Class<*>, val method: Method?)

    private class State(
        val log: MelodyLog,
        val loader: ClassLoader,
        val processName: String,
        val hostPackage: String,
        val installId: String,
        val lookup: DexAnchorLookup,
        val cached: Map<String, MelodyAnchorEntry>,
        val specs: Map<String, MelodyAnchorSpec>,
    ) {
        /** Class names already resolved this process, per anchor id. */
        val resolved = LinkedHashMap<String, List<String>>()

        /** Report rows, in resolution order; a multi-implementation anchor contributes several rows. */
        val entries = ArrayList<MelodyAnchorEntry>()

        var dexkitScans = 0
    }

    @Volatile
    private var state: State? = null

    val hostPackage: String? get() = state?.hostPackage
    val processName: String? get() = state?.processName
    val installId: String? get() = state?.installId

    /**
     * Opens the session for one process. [cached] is the report already persisted for [installId] (empty
     * when the host was updated or the cache is cold). A blank [apkPath] disables the DexKit level; the
     * loader/baseline levels still work.
     */
    fun begin(
        log: MelodyLog,
        loader: ClassLoader,
        processName: String,
        host: MelodyAnchorHost,
        installId: String,
        cached: MelodyAnchorProcessReport?,
        apkPath: String?,
        lookupFactory: (String) -> DexAnchorLookup = { DexKitAnchorLookup(it) },
    ) {
        val cachedSameInstall = cached?.anchors?.associateBy { it.id }.orEmpty()
        val lookup = if (apkPath.isNullOrBlank()) NoDexAnchorLookup else runCatching { lookupFactory(apkPath) }
            .getOrDefault(NoDexAnchorLookup)
        state = State(
            log = log,
            loader = loader,
            processName = processName,
            hostPackage = host.hostPackage,
            installId = installId,
            lookup = lookup,
            cached = cachedSameInstall,
            specs = MelodyAnchorCatalog.forHost(host).associateBy { it.id },
        )
        log.event(
            "melody.anchor.session",
            "process" to processName,
            "host" to host.hostPackage,
            "install" to installId,
            "cached" to cachedSameInstall.size,
            "dexkit" to (lookup !is NoDexAnchorLookup),
        )
    }

    /** Ends the session and returns the report BtRemix should persist; the DexKit bridge is closed. */
    fun finish(): MelodyAnchorProcessReport? {
        val current = state ?: return null
        val report = MelodyAnchorProcessReport(current.processName, current.entries.toList())
        runCatching { current.lookup.close() }
        current.log.event(
            "melody.anchor.resolved",
            "process" to current.processName,
            "hits" to report.hits,
            "total" to report.total,
            "dexkit_scans" to current.dexkitScans,
            "misses" to report.misses.joinToString(",") { it.id },
        )
        state = null
        return report
    }

    /** Test hook: drops any open session without producing a report. */
    fun reset() {
        runCatching { state?.lookup?.close() }
        state = null
    }

    /**
     * Test seam: opens a session whose anchors are exactly [specs] (the catalog is not consulted), so the
     * resolution policy can be exercised on the JVM with a fake [DexAnchorLookup] and fixture classes.
     */
    internal fun beginForTest(
        specs: List<MelodyAnchorSpec>,
        loader: ClassLoader,
        lookup: DexAnchorLookup,
        cached: Map<String, MelodyAnchorEntry> = emptyMap(),
        log: MelodyLog = MelodyLog(null),
    ) {
        state = State(
            log = log,
            loader = loader,
            processName = "test",
            hostPackage = MelodyAnchorHost.Melody.hostPackage,
            installId = "test-install",
            lookup = lookup,
            cached = cached,
            specs = specs.associateBy { it.id },
        )
    }

    /**
     * All concrete classes for [id]. Zero classes means "unresolvable" (the caller fail-opens); several
     * only happen for catalog entries explicitly marked `allowMultiple`.
     */
    fun classes(id: String, loader: ClassLoader = state?.loader ?: ClassLoader.getSystemClassLoader()): List<Class<*>> {
        val current = state
        val spec = current?.specs?.get(id) ?: MelodyAnchorCatalog.spec(id) ?: return emptyList()
        val names = resolve(spec, current, loader) ?: return emptyList()
        return names.mapNotNull { loadCandidate(it, loader) }
    }

    fun classOrNull(id: String, loader: ClassLoader = state?.loader ?: ClassLoader.getSystemClassLoader()): Class<*>? =
        classes(id, loader).firstOrNull()

    /** The single method anchor [id] (class + method); `null` when missing or ambiguous. */
    fun anchor(id: String, loader: ClassLoader = state?.loader ?: ClassLoader.getSystemClassLoader()): Anchor? =
        anchors(id, loader).firstOrNull()

    /** Every method anchor [id] resolved to (one for redirect/setgate/provider, two for `redirect.v0`). */
    fun anchors(id: String, loader: ClassLoader = state?.loader ?: ClassLoader.getSystemClassLoader()): List<Anchor> {
        val current = state
        val spec = current?.specs?.get(id) ?: MelodyAnchorCatalog.spec(id) ?: return emptyList()
        if (!spec.methodAnchor) return emptyList()
        val names = resolve(spec, current, loader) ?: return emptyList()
        return names.mapNotNull { name ->
            val cls = loadCandidate(name, loader) ?: return@mapNotNull null
            val method = methodOf(cls, spec, cachedMethodName(current, spec.id), loader, 0) ?: return@mapNotNull null
            Anchor(cls, method)
        }
    }

    // --- resolution ---------------------------------------------------------------------------------

    /**
     * Resolves [spec] to class names and records the report rows. Returns `null` when nothing resolved
     * (a miss is recorded as a `Missing` row).
     */
    private fun resolve(spec: MelodyAnchorSpec, current: State?, loader: ClassLoader): List<String>? {
        val cachedNames = current?.resolved?.get(spec.id)
        if (cachedNames != null) return cachedNames.takeIf { it.isNotEmpty() }

        if (current == null) {
            // No session (defensive): plain baseline lookup, no report.
            val cls = spec.baselineClasses.firstNotNullOfOrNull { loadBaseline(it, loader) } ?: return null
            return listOf(cls.name)
        }

        // 1. persisted cache for the same install fingerprint.
        val cached = current.cached[spec.id]
        if (cached != null && !cached.missing) {
            val cls = cached.className?.let { loadCandidate(it, loader) }
            if (cls != null &&
                (!spec.methodAnchor || methodOf(cls, spec, cached.methodName, loader, 0) != null) &&
                (spec.methodAnchor || matchesShape(cls, spec, loader))
            ) {
                return record(current, spec, listOf(cls.name), MelodyAnchorLevel.Cache, listOf(cached.methodName))
            }
        }

        // 2. baseline names (trusted as recorded).
        val baselineNames = ArrayList<String>()
        val baselineMethods = ArrayList<String?>()
        var renamed = false
        for (name in spec.baselineClasses) {
            val cls = loadBaseline(name, loader) ?: continue
            if (spec.methodAnchor) {
                val method = baselineMethod(cls, spec, loader) ?: continue
                val expected = (spec.query as? MelodyAnchorQuery.MethodSignature)?.methodName
                if (expected != null && method.name != expected) renamed = true
                baselineMethods += method.name
            } else {
                baselineMethods += null
            }
            baselineNames += cls.name
        }
        if (baselineNames.isNotEmpty() && (spec.allowMultiple || baselineNames.size == 1)) {
            val level = if (renamed) MelodyAnchorLevel.Rename else MelodyAnchorLevel.Baseline
            return record(current, spec, baselineNames, level, baselineMethods)
        }

        // 3. DexKit scan (global; the recorded package is obfuscated too).
        val candidates = dexCandidates(spec, current, loader)
        val chosen = when {
            candidates.isEmpty() -> emptyList()
            spec.allowMultiple -> candidates
            candidates.size == 1 -> candidates
            else -> {
                current.log.event("melody.anchor.ambiguous", "hook" to spec.id, "candidates" to candidates.size)
                emptyList()
            }
        }
        if (chosen.isNotEmpty()) {
            val methods = chosen.map { methodOf(it, spec, null, loader, 0)?.name }
            return record(current, spec, chosen.map { it.name }, MelodyAnchorLevel.Dexkit, methods)
        }

        // Several baseline classes resolved but the anchor does not allow multiple; keep them.
        if (baselineNames.isNotEmpty()) {
            return record(current, spec, baselineNames, MelodyAnchorLevel.Baseline, baselineMethods)
        }

        current.resolved[spec.id] = emptyList()
        current.entries += MelodyAnchorEntry(spec.id, null, null, MelodyAnchorLevel.Missing)
        current.log.event(
            "melody.anchor.missing",
            "hook" to spec.id,
            "class" to spec.baselineClasses.joinToString(","),
            "feature" to spec.feature,
        )
        return null
    }

    private fun record(
        current: State,
        spec: MelodyAnchorSpec,
        names: List<String>,
        level: MelodyAnchorLevel,
        methods: List<String?>,
    ): List<String> {
        current.resolved[spec.id] = names
        names.forEachIndexed { index, name ->
            val method = methods.getOrNull(index)
            current.entries += MelodyAnchorEntry(spec.id, name, method, level)
            current.log.event(
                "melody.anchor.hit",
                "hook" to spec.id,
                "level" to level.wire,
                "class" to name,
                "method" to (method ?: "-"),
            )
        }
        return names
    }

    private fun dexCandidates(spec: MelodyAnchorSpec, current: State, loader: ClassLoader): List<Class<*>> {
        if (spec.query == MelodyAnchorQuery.BaselineOnly) return emptyList()
        current.dexkitScans += 1
        val resolveType: (MelodyTypeRef) -> Class<*>? = { ref -> resolveType(ref, loader, 0) }
        val names = runCatching { current.lookup.findClasses(spec, resolveType) }
            .onFailure { current.log.warn("melody.anchor.dexkit_failed", it) }
            .getOrDefault(emptyList())
        val resolved = ArrayList<Class<*>>()
        for (name in names) {
            val cls = loadCandidate(name, loader) ?: continue
            if (!spec.allowAbstract && java.lang.reflect.Modifier.isAbstract(cls.modifiers)) continue
            if (spec.methodAnchor && methodOf(cls, spec, null, loader, 0) == null) continue
            if (!spec.methodAnchor && !matchesShape(cls, spec, loader)) continue
            resolved += cls
        }
        return resolved.distinct()
    }

    // --- types / methods ----------------------------------------------------------------------------

    private fun resolveType(ref: MelodyTypeRef, loader: ClassLoader, depth: Int): Class<*>? {
        if (depth > MAX_TYPE_DEPTH) return null
        return when (ref) {
            is MelodyTypeRef.Of -> ref.clazz
            is MelodyTypeRef.Named -> Reflect.loadClass(ref.name, loader)
            is MelodyTypeRef.AnchorRef -> classes(ref.id, loader).firstOrNull()
        }
    }

    private fun baselineMethod(cls: Class<*>, spec: MelodyAnchorSpec, loader: ClassLoader): Method? {
        val query = spec.query
        if (query !is MelodyAnchorQuery.MethodSignature) return null
        return findMethodFor(cls, query.methodName, query.params, loader)
    }

    private fun methodOf(cls: Class<*>, spec: MelodyAnchorSpec, hint: String?, loader: ClassLoader, depth: Int): Method? {
        val query = spec.query
        if (query !is MelodyAnchorQuery.MethodSignature) return null
        return findMethodFor(cls, hint ?: query.methodName, query.params, loader)
    }

    /**
     * Finds a declared method by an optional name and a parameter shape in which any entry that cannot be
     * resolved (a host type we could not load) is treated as a wildcard.
     */
    private fun findMethodFor(cls: Class<*>, name: String?, params: List<MelodyTypeRef?>, loader: ClassLoader): Method? {
        val types = params.map { ref -> ref?.let { resolveType(it, loader, 0) } }
        if (name != null) {
            declaredMethod(cls, name, types)?.let { return it }
        }
        // No (or no longer a) matching name: fall back to a unique shape match, which is how a renamed
        // method on a kept class is found and reported as `melody.anchor.renamed`.
        return uniqueMethodByShape(cls, types)
    }

    private fun declaredMethod(cls: Class<*>, name: String, types: List<Class<*>?>): Method? {
        val exact = types.all { it != null }
        for (owner in Reflect.hierarchyOf(cls)) {
            for (method in runCatching { owner.declaredMethods }.getOrNull().orEmpty()) {
                if (method.isSynthetic || method.isBridge) continue
                if (method.name != name) continue
                if (method.parameterTypes.size != types.size) continue
                if (exact && !method.parameterTypes.contentEquals(types.filterNotNull().toTypedArray())) continue
                if (!exact && !wildcardMatch(method.parameterTypes, types)) continue
                runCatching { method.isAccessible = true }
                return method
            }
        }
        return null
    }

    /** The single declared method with the given shape, regardless of name; `null` when ambiguous. */
    private fun uniqueMethodByShape(cls: Class<*>, types: List<Class<*>?>): Method? {
        val exact = types.all { it != null }
        var match: Method? = null
        for (owner in Reflect.hierarchyOf(cls)) {
            for (method in runCatching { owner.declaredMethods }.getOrNull().orEmpty()) {
                if (method.isSynthetic || method.isBridge) continue
                if (method.parameterTypes.size != types.size) continue
                if (exact && !method.parameterTypes.contentEquals(types.filterNotNull().toTypedArray())) continue
                if (!exact && !wildcardMatch(method.parameterTypes, types)) continue
                if (match != null && match.name != method.name) return null
                match = method
            }
        }
        return match?.also { runCatching { it.isAccessible = true } }
    }

    private fun wildcardMatch(actual: Array<Class<*>>, expected: List<Class<*>?>): Boolean {
        for (index in actual.indices) {
            val want = expected.getOrNull(index) ?: continue
            if (!want.isAssignableFrom(actual[index]) && !actual[index].isAssignableFrom(want)) return false
        }
        return true
    }

    /** Light verification for [MelodyAnchorQuery.Structural] class anchors (method names/arities). */
    private fun matchesShape(cls: Class<*>, spec: MelodyAnchorSpec, loader: ClassLoader): Boolean {
        val query = spec.query as? MelodyAnchorQuery.Structural ?: return true
        for (shape in query.methods) {
            if (!declaresShape(cls, shape, loader)) return false
        }
        return true
    }

    private fun declaresShape(cls: Class<*>, shape: MelodyMethodShape, loader: ClassLoader): Boolean {
        val types = shape.params.map { ref -> ref?.let { resolveType(it, loader, 0) } }
        for (owner in Reflect.hierarchyOf(cls)) {
            for (method in runCatching { owner.declaredMethods }.getOrNull().orEmpty()) {
                if (shape.name != null && method.name != shape.name) continue
                if (method.parameterTypes.size != types.size) continue
                if (!wildcardMatch(method.parameterTypes, types)) continue
                return true
            }
        }
        return false
    }

    private fun cachedMethodName(current: State?, id: String): String? = current?.cached?.get(id)?.methodName

    /** Baseline names are our own constants; no prefix filter applies. */
    private fun loadBaseline(name: String, loader: ClassLoader): Class<*>? =
        if (name.isEmpty()) null else Reflect.loadClass(name, loader)

    /** Candidate names (DexKit answers, persisted cache) must clear the platform denylist. */
    private fun loadCandidate(name: String, loader: ClassLoader): Class<*>? {
        if (!MelodyAnchorBroadcast.isRelocatableHostClass(name)) return null
        return Reflect.loadClass(name, loader)
    }

    private const val MAX_TYPE_DEPTH = 4
}

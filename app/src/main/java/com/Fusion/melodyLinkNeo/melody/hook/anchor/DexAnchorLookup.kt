package com.fusion.melodyLinkNeo.melody.hook.anchor

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher

/**
 * The DexKit half of the anchor resolver, behind an interface so the resolution *policy* (baseline first,
 * ambiguity is a miss, platform-package denylist) is JVM-testable with a fake - DexKit itself needs
 * native libraries and a real APK.
 *
 * [resolveType] is supplied by the session: it turns a [MelodyTypeRef] into a loadable `Class<*>` (a
 * framework class, a stable host class by name, or another anchor that was resolved first). A type that
 * cannot be resolved becomes a wildcard rather than failing the whole query.
 */
internal interface DexAnchorLookup {

    /** Candidate class names for [spec], in no particular order; empty when nothing matched. */
    fun findClasses(spec: MelodyAnchorSpec, resolveType: (MelodyTypeRef) -> Class<*>?): List<String>

    /** Releases the underlying parse state. */
    fun close()
}

/** [DexAnchorLookup] that never matches; used when the host APK path is unavailable. */
internal object NoDexAnchorLookup : DexAnchorLookup {
    override fun findClasses(spec: MelodyAnchorSpec, resolveType: (MelodyTypeRef) -> Class<*>?): List<String> =
        emptyList()

    override fun close() = Unit
}

/**
 * Real DexKit lookup. One instance owns exactly one [DexKitBridge] and is shared by the whole resolution
 * pass.
 *
 * Scans are **global**: the recorded packages are obfuscated too (`c7`, `D0`, `A9`, `n7`, `Ba`, `i9` all
 * change per release), so a package-scoped query silently matches nothing. Precision comes from the shape
 * instead - framework/stable types survive R8, and an ambiguous answer is treated as a miss by the
 * session.
 */
internal class DexKitAnchorLookup(apkPath: String) : DexAnchorLookup {

    private val bridge: DexKitBridge? = runCatching { DexKitBridge.create(apkPath) }.getOrNull()

    override fun findClasses(spec: MelodyAnchorSpec, resolveType: (MelodyTypeRef) -> Class<*>?): List<String> {
        val kit = bridge ?: return emptyList()
        return when (val query = spec.query) {
            is MelodyAnchorQuery.MethodSignature -> findMethodClasses(kit, query, resolveType)
            is MelodyAnchorQuery.MethodName -> findMethodClasses(kit, query)
            is MelodyAnchorQuery.SuperType -> findSuperTypeClasses(kit, query, resolveType)
            is MelodyAnchorQuery.Structural -> findStructuralClasses(kit, query, resolveType)
            MelodyAnchorQuery.BaselineOnly -> emptyList()
        }
    }

    private fun findMethodClasses(
        kit: DexKitBridge,
        query: MelodyAnchorQuery.MethodSignature,
        resolveType: (MelodyTypeRef) -> Class<*>?,
    ): List<String> {
        val matcher = MethodMatcher.create()
            .paramTypes(*query.params.map { ref -> ref?.let { resolveType(it) } }.toTypedArray())
        query.methodName?.let { matcher.name(it) }
        query.returnType?.let { ref -> resolveType(ref) }?.let { matcher.returnType(it) }
        return kit.findMethod(FindMethod.create().matcher(matcher))
            .map { it.declaredClassName }
            .distinct()
    }

    private fun findMethodClasses(kit: DexKitBridge, query: MelodyAnchorQuery.MethodName): List<String> {
        val matcher = MethodMatcher.create().name(query.name)
        query.paramCount?.let { matcher.paramCount(it) }
        return kit.findMethod(FindMethod.create().matcher(matcher))
            .map { it.declaredClassName }
            .distinct()
    }

    private fun findSuperTypeClasses(
        kit: DexKitBridge,
        query: MelodyAnchorQuery.SuperType,
        resolveType: (MelodyTypeRef) -> Class<*>?,
    ): List<String> {
        val superClass = resolveType(query.superClass) ?: return emptyList()
        val classMatcher = ClassMatcher.create().superClass(superClass.name)
        query.stringConstant?.let { classMatcher.usingStrings(it) }
        return kit.findClass(FindClass.create().matcher(classMatcher))
            .map { it.name }
            .distinct()
    }

    private fun findStructuralClasses(
        kit: DexKitBridge,
        query: MelodyAnchorQuery.Structural,
        resolveType: (MelodyTypeRef) -> Class<*>?,
    ): List<String> {
        val methods = MethodsMatcher.create()
        for (shape in query.methods) {
            val matcher = MethodMatcher.create()
                .paramTypes(*shape.params.map { ref -> ref?.let { resolveType(it) } }.toTypedArray())
            shape.name?.let { matcher.name(it) }
            shape.returnType?.let { ref -> resolveType(ref) }?.let { matcher.returnType(it) }
            methods.add(matcher)
        }
        val classMatcher = ClassMatcher.create().methods(methods)
        val min = query.methodCountMin
        val max = query.methodCountMax
        if (min != null || max != null) {
            classMatcher.methodCount(min = min ?: 0, max = max ?: Int.MAX_VALUE)
        }
        return kit.findClass(FindClass.create().matcher(classMatcher))
            .map { it.name }
            .distinct()
    }

    override fun close() {
        runCatching { bridge?.close() }
    }
}

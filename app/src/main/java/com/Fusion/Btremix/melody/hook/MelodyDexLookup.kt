package com.Fusion.Btremix.melody.hook

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher

/**
 * DexKit fallback for the short-name transport anchors (M3.4 plan §4, risk table "短名锚点漂移").
 *
 * The suppression layers sit on `BRClientDevice`/`BaseBRConnection`, which the analysis report scores
 * ★☆☆☆☆ for stability: the host keeps the classes but renames them on every obfuscated release. The
 * primary resolution therefore uses the names recorded for the 17.6.3 baseline, and this lookup only
 * runs when that fails.
 *
 * The class is found by *method signature* rather than by name: the parameter types (`UUID`,
 * `byte[], byte[], long`) are framework types the host cannot rename, and the declaring package of the
 * btsdk internals stays one of the two short packages. An ambiguous answer (two classes with the same
 * signature) is treated as "not found" - hooking the wrong class is worse than degrading, because
 * these layers blind-write into the host's connection objects.
 *
 * DexKit parses the host APK inside the host process and is therefore wrapped by the caller in
 * `runCatching`; a failure only costs the anchor, never the host.
 */
internal object MelodyDexLookup {

    /**
     * Name of the single class in [packages] declaring a method with exactly [params] and a `void`
     * return, or `null` when the answer is missing or ambiguous.
     */
    fun findClassWithVoidMethod(
        hostApkPath: String?,
        packages: Collection<String>,
        params: Array<Class<*>>,
    ): String? = findClassNamesWithMethod(hostApkPath, packages, params, Void.TYPE).singleOrNull()

    /**
     * Every class in [packages] declaring a method with exactly [params] and an optional [returnType].
     *
     * The v0 redirect anchor (M5.1) has the same signature on the abstract repository base (`b`) and
     * on the concrete `EarphoneRepositoryClientImpl`, so the caller gets the full candidate list and
     * picks the concrete implementation itself - a `singleOrNull` here would wrongly report "missing".
     */
    fun findClassNamesWithMethod(
        hostApkPath: String?,
        packages: Collection<String>,
        params: Array<Class<*>>,
        returnType: Class<*>? = null,
    ): List<String> {
        if (hostApkPath.isNullOrBlank()) return emptyList()
        val bridge = runCatching { DexKitBridge.create(hostApkPath) }.getOrNull() ?: return emptyList()
        return bridge.use { kit ->
            val matcher = MethodMatcher.create()
                .paramTypes(*params)
            if (returnType != null) matcher.returnType(returnType)
            val methods = runCatching {
                kit.findMethod(FindMethod.create().searchPackages(packages).matcher(matcher))
            }.getOrNull().orEmpty()
            methods.map { it.declaredClassName }.distinct()
        }
    }

    /**
     * Name of the single class in [packages] declaring a no-argument method named [methodName] with
     * return type [returnType]. Used to find the host's ANC command-state DTO (`O`, `SetCommandStateDTO`)
     * by its accessor rather than by its obfuscated class name (M5.1 D-19).
     */
    fun findClassDeclaringMethod(
        hostApkPath: String?,
        packages: Collection<String>,
        methodName: String,
        params: Array<Class<*>> = emptyArray(),
        returnType: Class<*>? = null,
    ): String? {
        if (hostApkPath.isNullOrBlank()) return null
        val bridge = runCatching { DexKitBridge.create(hostApkPath) }.getOrNull() ?: return null
        return bridge.use { kit ->
            val matcher = MethodMatcher.create()
                .name(methodName)
                .paramTypes(*params)
            if (returnType != null) matcher.returnType(returnType)
            val methods = runCatching {
                kit.findMethod(FindMethod.create().searchPackages(packages).matcher(matcher))
            }.getOrNull().orEmpty()
            methods.map { it.declaredClassName }.distinct().singleOrNull()
        }
    }
}

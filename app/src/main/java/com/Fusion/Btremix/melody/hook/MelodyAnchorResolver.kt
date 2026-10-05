package com.Fusion.Btremix.melody.hook

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Three-level host anchor resolution, extracted for the M5.1 redirect anchor
 * (`HANDOFF_MELODY_M5_PLAN.md` §4 M5.1/§4 M5.4).
 *
 * The obfuscated baseline (17.6.3) is always tried first, and only by *shape* when the recorded name
 * no longer exists:
 *
 *  1. baseline class name + method name + parameter types;
 *  2. baseline class name + parameter types (the method was renamed, the class kept);
 *  3. DexKit scan of the recorded packages by parameter types (+ return type when given), picking the
 *     concrete (non-abstract) declaring class.
 *
 * A miss is reported as `melody.anchor.missing` and costs only the caller's own feature; it never
 * throws into the host. `melody.anchor.renamed` is emitted whenever level 2/3 had to be used, so a host
 * update is visible in logcat before it becomes a functional regression.
 */
internal class MelodyAnchorResolver(
    private val log: MelodyLog,
    private val loader: ClassLoader,
    /** `ApplicationInfo.sourceDir` of the host, used only by the DexKit level. */
    private val hostApkPath: String?,
) {

    data class Anchor(val clazz: Class<*>, val method: Method)

    fun resolve(
        hook: String,
        baselineClass: String,
        packages: List<String>,
        methodName: String,
        params: Array<Class<*>>,
        returnType: Class<*>? = null,
    ): Anchor? = resolveAll(hook, listOf(baselineClass), packages, methodName, params, returnType).firstOrNull()

    /**
     * Transport-style anchor (M5.4 D-30): the layer method has no recorded name, so it is matched by
     * its parameter signature and a `void` return. The DexKit level keeps the original "exactly one
     * candidate" requirement - an ambiguous scan must not hook an unrelated method.
     */
    fun resolveVoid(
        hook: String,
        baselineClass: String,
        packages: List<String>,
        params: Array<Class<*>>,
    ): Anchor? {
        val cls = Reflect.loadClass(baselineClass, loader)
        val method = cls?.let { Reflect.findUniqueMethodByParams(it, params) }
        if (cls != null && method != null) {
            hit(hook, "baseline", cls.name, method.name)
            return Anchor(cls, method)
        }
        val found = runCatching {
            MelodyDexLookup.findClassNamesWithMethod(hostApkPath, packages, params, java.lang.Void.TYPE).singleOrNull()
        }
            .onFailure { log.warn("melody.anchor.dexkit_failed", it) }
            .getOrNull()
        val resolvedClass = found?.let { Reflect.loadClass(it, loader) }
        val resolvedMethod = resolvedClass?.let { Reflect.findUniqueMethodByParams(it, params) }
        if (resolvedClass != null && resolvedMethod != null) {
            log.event(
                "melody.anchor.renamed",
                "hook" to hook,
                "expected" to baselineClass,
                "resolved" to resolvedClass.name,
            )
            hit(hook, "dexkit", resolvedClass.name, resolvedMethod.name)
            return Anchor(resolvedClass, resolvedMethod)
        }
        log.event("melody.anchor.missing", "hook" to hook, "class" to baselineClass, "method" to "(params)")
        return null
    }

    /**
     * All concrete declarations of one contract, across the recorded baseline classes.
     *
     * A single baseline is not enough for `earphone/b;->v0`: 17.6.3 ships **two** concrete
     * implementations (`EarphoneRepositoryClientImpl` in `:fg`, `J` in the main process), and only
     * hooking both covers every official ANC entrance. Each recorded baseline is tried by name first;
     * if one is missing or did not resolve, the DexKit level runs and picks up every other concrete
     * class with the same signature (renames included). The steady state (all baselines present) never
     * pays for DexKit.
     */
    fun resolveAll(
        hook: String,
        baselineClasses: List<String>,
        packages: List<String>,
        methodName: String,
        params: Array<Class<*>>,
        returnType: Class<*>? = null,
    ): List<Anchor> {
        val anchors = LinkedHashMap<String, Anchor>()
        var missedBaseline = false
        for (baseline in baselineClasses) {
            val cls = Reflect.loadClass(baseline, loader)
            if (cls == null) {
                missedBaseline = true
                continue
            }
            val method = Reflect.findMethod(cls, methodName, params)
                ?: Reflect.findUniqueMethodByParams(cls, params)
            if (method == null) {
                missedBaseline = true
                continue
            }
            if (method.name != methodName) {
                log.event(
                    "melody.anchor.renamed",
                    "hook" to hook,
                    "expected" to "$baseline.$methodName",
                    "resolved" to "${cls.name}.${method.name}",
                )
            }
            hit(hook, if (method.name == methodName) "baseline" else "rename", cls.name, method.name)
            anchors[cls.name] = Anchor(cls, method)
        }

        if (missedBaseline || anchors.isEmpty()) {
            val candidates = runCatching {
                MelodyDexLookup.findClassNamesWithMethod(hostApkPath, packages, params, returnType)
            }
                .onFailure { log.warn("melody.anchor.dexkit_failed", it) }
                .getOrNull()
                .orEmpty()
            for (name in candidates) {
                if (name in anchors) continue
                val cls = Reflect.loadClass(name, loader) ?: continue
                if (Modifier.isAbstract(cls.modifiers)) continue
                val method = Reflect.findUniqueMethodByParams(cls, params) ?: continue
                log.event(
                    "melody.anchor.renamed",
                    "hook" to hook,
                    "expected" to baselineClasses.firstOrNull(),
                    "resolved" to name,
                )
                hit(hook, "dexkit", name, method.name)
                anchors[name] = Anchor(cls, method)
            }
        }

        if (anchors.isEmpty()) {
            log.event(
                "melody.anchor.missing",
                "hook" to hook,
                "class" to baselineClasses.joinToString(","),
                "method" to methodName,
            )
        }
        return anchors.values.toList()
    }

    /** One line per resolved anchor; the export groups these into the "锚点命中表" (M5.4 D-30). */
    private fun hit(hook: String, level: String, className: String, methodName: String) {
        log.event(
            "melody.anchor.hit",
            "hook" to hook,
            "level" to level,
            "class" to className,
            "method" to methodName,
        )
    }
}

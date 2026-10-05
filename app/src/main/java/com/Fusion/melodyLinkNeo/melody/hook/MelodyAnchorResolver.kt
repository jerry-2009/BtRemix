package com.fusion.melodyLinkNeo.melody.hook

import com.fusion.melodyLinkNeo.melody.hook.anchor.MelodyAnchorCatalog
import java.lang.reflect.Method

/**
 * The hook-facing view of [MelodyAnchorSession] (M5.4 D-30, retargeted at the M6 catalog).
 *
 * Before M6 this class carried the three-level resolution itself; it now delegates to the process-scoped
 * session so every hook (transport, the three redirects, and the M4 panel/DTO/card anchors) shares one
 * DexKit pass and one persisted report. The constructor keeps its `(log, loader, hostApkPath)` shape so
 * the existing call sites do not change; only the catalog id ([hook]) selects the anchor, which is why
 * the ids must stay equal to the `hook=` values recorded in M5.
 */
internal class MelodyAnchorResolver(
    private val log: MelodyLog,
    private val loader: ClassLoader,
    @Suppress("unused") private val hostApkPath: String? = null,
) {

    data class Anchor(val clazz: Class<*>, val method: Method)

    /**
     * The single anchor for [hook]. The remaining parameters are the pre-M6 fallback description and are
     * only used when `hook` is not in the catalog (kept so a future ad-hoc anchor still resolves by name).
     */
    fun resolve(
        hook: String,
        baselineClass: String,
        packages: List<String>,
        methodName: String,
        params: Array<Class<*>>,
        returnType: Class<*>? = null,
    ): Anchor? = resolveAll(hook, listOf(baselineClass), packages, methodName, params, returnType).firstOrNull()

    /** Transport-style anchor: matched by parameter signature and a `void` return. */
    fun resolveVoid(
        hook: String,
        baselineClass: String,
        packages: List<String>,
        params: Array<Class<*>>,
    ): Anchor? = resolveAll(hook, listOf(baselineClass), packages, methodName = "", params = params).firstOrNull()

    /**
     * Every concrete declaration of one contract. Only `redirect.v0` needs more than one (the main
     * process and `:fg` implementations), and the catalog marks it `allowMultiple`.
     */
    fun resolveAll(
        hook: String,
        baselineClasses: List<String>,
        packages: List<String>,
        methodName: String,
        params: Array<Class<*>>,
        returnType: Class<*>? = null,
    ): List<Anchor> {
        val resolved = if (MelodyAnchorCatalog.spec(hook) != null) {
            MelodyAnchorSession.anchors(hook, loader).map { Anchor(it.clazz, it.method!!) }
        } else {
            emptyList()
        }
        if (resolved.isNotEmpty()) return resolved
        if (MelodyAnchorCatalog.spec(hook) != null) return emptyList()
        // Not in the catalog: behave like the pre-M6 resolver (baseline name first, then signature).
        val anchors = LinkedHashMap<String, Anchor>()
        for (baseline in baselineClasses) {
            val cls = Reflect.loadClass(baseline, loader) ?: continue
            val method = if (methodName.isEmpty()) {
                Reflect.findUniqueMethodByParams(cls, params)
            } else {
                Reflect.findMethod(cls, methodName, params) ?: Reflect.findUniqueMethodByParams(cls, params)
            } ?: continue
            if (methodName.isNotEmpty() && method.name != methodName) {
                log.event(
                    "melody.anchor.renamed",
                    "hook" to hook,
                    "expected" to "$baseline.$methodName",
                    "resolved" to "${cls.name}.${method.name}",
                )
            }
            anchors[cls.name] = Anchor(cls, method)
        }
        if (anchors.isEmpty()) {
            log.event("melody.anchor.missing", "hook" to hook, "class" to baselineClasses.joinToString(","))
        }
        return anchors.values.toList()
    }
}

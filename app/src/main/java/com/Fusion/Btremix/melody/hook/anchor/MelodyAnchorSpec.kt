package com.Fusion.Btremix.melody.hook.anchor

/**
 * One host anchor in the shared catalog (`HANDOFF_MELODY_M6_PLAN.md`, revised after the first real host
 * update).
 *
 * The first M6 cut scoped every DexKit query to the *recorded package* (`c7`, `D0`, `A9`, `n7`, `Ba`, …)
 * and guarded results with a `com.oplus./com.coui.` allow list. A real update showed why that cannot
 * work: R8 renames the short packages too, so the scope matched nothing, and the allow list additionally
 * rejected the module's own baseline classes (`c7.b`, `D0.d`, `A9.f`, `i9.c`, `Ba.z`, `n7.e$a` - none of
 * them live under `com.oplus.`).
 *
 * The catalog therefore describes anchors by *shape* - framework/stable class references, method
 * parameter and return types, and class-level fingerprints - and the DexKit scan runs over the whole
 * APK. A candidate only has to survive the platform-package denylist (see `MelodyAnchorSession`), which
 * keeps a false positive from aiming a hook at `androidx.*`/`java.*`.
 */

internal enum class MelodyAnchorHost(val hostPackage: String) {
    /** `com.oplus.melody` (the detail page / device centre / card hooks). */
    Melody("com.oplus.melody"),

    /** `com.oplus.wirelesssettings` (the Bluetooth detail page "耳机功能" row). */
    Settings("com.oplus.wirelesssettings"),
}

/**
 * A type reference in a matcher.
 *
 * `Of` is a class the module can name at compile time (framework/library types, always rename-proof).
 * `Named` is a host class whose *name is stable* (an SDK/DTO class R8 keeps, e.g. `EarphoneDTO`).
 * `AnchorRef` is another catalog anchor's resolved class, which is how a shape can depend on something
 * that was itself relocated (e.g. the card sender's `(CardValue) -> void` method).
 */
internal sealed interface MelodyTypeRef {
    data class Of(val clazz: Class<*>) : MelodyTypeRef
    data class Named(val name: String) : MelodyTypeRef
    data class AnchorRef(val id: String) : MelodyTypeRef
}

/** A method the class has to declare, used by [MelodyAnchorQuery.Structural]. */
internal data class MelodyMethodShape(
    val name: String?,
    val params: List<MelodyTypeRef?> = emptyList(),
    val returnType: MelodyTypeRef? = null,
)

/** How a DexKit scan locates the class. */
internal sealed interface MelodyAnchorQuery {

    /**
     * Match the class declaring a method with exactly [params] and [returnType]. A `null` entry is a
     * wildcard ("any type here"), which is what lets a matcher survive one unstable parameter.
     */
    data class MethodSignature(
        val methodName: String?,
        val params: List<MelodyTypeRef?>,
        val returnType: MelodyTypeRef?,
    ) : MelodyAnchorQuery

    /** Match the class declaring a method named [name] (optionally with exactly [paramCount] params). */
    data class MethodName(val name: String, val paramCount: Int? = null) : MelodyAnchorQuery

    /** Match a class extending [superClass], optionally restricted to classes using [stringConstant]. */
    data class SuperType(val superClass: MelodyTypeRef, val stringConstant: String? = null) : MelodyAnchorQuery

    /**
     * Match a class by a fingerprint of its own methods - for R8-merged lambda/data classes whose
     * "identity" is the set of methods they declare rather than any single signature. [methods] must all
     * be present; the optional count bounds keep a megamorphic merged class from matching by accident.
     */
    data class Structural(
        val methods: List<MelodyMethodShape>,
        val methodCountMin: Int? = null,
        val methodCountMax: Int? = null,
    ) : MelodyAnchorQuery

    /**
     * No DexKit discriminator is available (the recorded name is the only handle). The anchor is still
     * cataloged, reported and prompted - it simply cannot be relocated automatically.
     */
    data object BaselineOnly : MelodyAnchorQuery
}

/**
 * A single resolvable host anchor.
 *
 * [methodAnchor] marks the anchors whose *method* is hooked (transport/redirect). Class anchors resolve
 * to a `Class<*>` only; their hooks then keep resolving the method by name/shape exactly as before.
 *
 * [allowMultiple] is for anchors that legitimately resolve to several classes (`redirect.v0` has one per
 * process). [allowAbstract] is for `transport.write`, whose target (`BaseBRConnection`) is abstract - the
 * hook installs on the declared method, which is fine.
 */
internal data class MelodyAnchorSpec(
    val id: String,
    val host: MelodyAnchorHost,
    val feature: String,
    val baselineClasses: List<String>,
    val packages: List<String>,
    val query: MelodyAnchorQuery,
    val methodAnchor: Boolean = false,
    val allowMultiple: Boolean = false,
    val allowAbstract: Boolean = false,
)

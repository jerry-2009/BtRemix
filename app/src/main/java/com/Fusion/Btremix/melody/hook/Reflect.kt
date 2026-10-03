package com.Fusion.Btremix.melody.hook

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Reflection helpers for the host (`com.oplus.melody`) classpath.
 *
 * The module compiles against the Android SDK only: the host ships R8-minified
 * `androidx.preference`, COUI widgets and its own btsdk types, so nothing from those packages may be
 * referenced by name at compile time. Every lookup here is name/parameter-shape based, tolerant of
 * renames, and returns `null` instead of throwing — M1 must never break the host.
 */
internal object Reflect {

    fun loadClass(name: String, loader: ClassLoader): Class<*>? = runCatching {
        Class.forName(name, false, loader)
    }.getOrNull()

    /** Walks the hierarchy (including [cls]) looking for a declared method with the exact shape. */
    fun findMethod(cls: Class<*>?, name: String, params: Array<Class<*>>): Method? {
        if (cls == null) return null
        for (type in hierarchyOf(cls)) {
            val method = runCatching { type.getDeclaredMethod(name, *params) }.getOrNull()
            if (method != null) {
                runCatching { method.isAccessible = true }
                return method
            }
        }
        return null
    }

    /**
     * Fallback for R8-renamed methods: returns the single method on the hierarchy whose parameter
     * types match exactly, regardless of its name. Ambiguity (two differently named candidates)
     * returns `null`, because guessing the wrong method is worse than reporting a missing anchor.
     */
    fun findUniqueMethodByParams(cls: Class<*>?, params: Array<Class<*>>): Method? {
        if (cls == null) return null
        var match: Method? = null
        for (type in hierarchyOf(cls)) {
            for (method in runCatching { type.declaredMethods }.getOrNull().orEmpty()) {
                if (method.isSynthetic || method.isBridge) continue
                if (method.parameterTypes.size != params.size) continue
                if (!method.parameterTypes.contentEquals(params)) continue
                if (match != null && match.name != method.name) return null
                match = method
            }
        }
        return match?.also { runCatching { it.isAccessible = true } }
    }

    /** Finds a declared no-argument method by walking the hierarchy. */
    fun findNoArgMethod(cls: Class<*>?, name: String): Method? = findMethod(cls, name, emptyArray())

    /**
     * Like [findMethod] but only accepts a declaration owned by the host (`com.oplus.*`). This keeps
     * e.g. an `onResume` hook limited to `DetailMainActivity` instead of firing for every Activity in
     * the process through `Activity.onResume`.
     */
    fun findHostDeclaredMethod(cls: Class<*>?, name: String, params: Array<Class<*>> = emptyArray()): Method? {
        if (cls == null) return null
        for (type in hierarchyOf(cls)) {
            if (!type.name.startsWith(HOST_PACKAGE_PREFIX)) continue
            val method = runCatching { type.getDeclaredMethod(name, *params) }.getOrNull() ?: continue
            runCatching { method.isAccessible = true }
            return method
        }
        return null
    }

    fun call(target: Any?, name: String): Any? {
        if (target == null) return null
        val method = findNoArgMethod(target.javaClass, name) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    fun callBoolean(target: Any?, name: String): Boolean? = call(target, name) as? Boolean

    fun callInt(target: Any?, name: String): Int? = call(target, name) as? Int

    /** Invokes a single-`int`-argument method by name (e.g. `PreferenceGroup.getPreference(int)`). */
    fun callWithInt(target: Any?, name: String, value: Int): Any? {
        if (target == null) return null
        val method = findMethod(target.javaClass, name, arrayOf(Int::class.javaPrimitiveType!!)) ?: return null
        return runCatching { method.invoke(target, value) }.getOrNull()
    }

    fun callString(target: Any?, name: String): String? = call(target, name)?.toString()

    fun callCharSequence(target: Any?, name: String): CharSequence? = call(target, name) as? CharSequence

    /** Reads the first readable instance field matching one of [names] on the hierarchy. */
    fun readField(target: Any?, vararg names: String): Any? {
        if (target == null) return null
        for (type in hierarchyOf(target.javaClass)) {
            for (field in runCatching { type.declaredFields }.getOrNull().orEmpty()) {
                if (field.isSynthetic || Modifier.isStatic(field.modifiers)) continue
                if (names.none { it == field.name }) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()
                if (value != null) return value
            }
        }
        return null
    }

    fun hierarchyOf(cls: Class<*>): Sequence<Class<*>> =
        generateSequence(cls) { it.superclass }.takeWhile { it != Any::class.java }

    private const val HOST_PACKAGE_PREFIX = "com.oplus."
}

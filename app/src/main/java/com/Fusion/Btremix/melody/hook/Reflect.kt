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

    /** Invokes a single-argument method by name and returns its value (e.g. `PreferenceGroup.findPreference`). */
    fun callSingleArg(target: Any?, name: String, value: Any?): Any? {
        if (target == null) return null
        for (owner in hierarchyOf(target.javaClass)) {
            for (method in runCatching { owner.declaredMethods }.getOrNull().orEmpty()) {
                if (method.name != name || method.parameterTypes.size != 1) continue
                if (value != null && !method.parameterTypes[0].isInstance(value)) continue
                val result = runCatching {
                    method.isAccessible = true
                    method.invoke(target, value)
                }.getOrNull()
                if (result != null) return result
            }
        }
        return null
    }

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

    /**
     * Reads the first readable instance field whose declared type matches [type].
     *
     * M3.4 needs this to find the host's own Bluetooth handles without knowing the obfuscated field
     * names (`BRClientDevice`/`BaseBRConnection` are re-obfuscated every release, but the field *types*
     * - `BluetoothDevice`, `BluetoothSocket` - are framework classes the host cannot rename).
     */
    fun readFieldOfType(target: Any?, type: Class<*>): Any? {
        if (target == null) return null
        for (owner in hierarchyOf(target.javaClass)) {
            for (field in runCatching { owner.declaredFields }.getOrNull().orEmpty()) {
                if (field.isSynthetic || Modifier.isStatic(field.modifiers)) continue
                if (!type.isAssignableFrom(field.type)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()
                if (value != null) return value
            }
        }
        return null
    }

    /** Reads the first non-null `String` field on the hierarchy that satisfies [predicate]. */
    fun readStringFieldWhere(target: Any?, predicate: (String) -> Boolean): String? {
        if (target == null) return null
        for (owner in hierarchyOf(target.javaClass)) {
            for (field in runCatching { owner.declaredFields }.getOrNull().orEmpty()) {
                if (field.isSynthetic || Modifier.isStatic(field.modifiers)) continue
                if (field.type != String::class.java) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target) as? String
                }.getOrNull() ?: continue
                if (predicate(value)) return value
            }
        }
        return null
    }

    /**
     * Writes an instance field by any of [names], coercing [value] to the field's own type.
     *
     * Used as the fallback when the host's setter is missing or was renamed: the `DeviceInfo` fields
     * (`mProductId`, `mIsSupportSpp`, ...) are the ones the analysis report lists as stable.
     */
    fun writeField(target: Any?, names: Array<String>, value: Any?): Boolean {
        if (target == null) return false
        for (owner in hierarchyOf(target.javaClass)) {
            for (field in runCatching { owner.declaredFields }.getOrNull().orEmpty()) {
                if (field.isSynthetic || Modifier.isStatic(field.modifiers)) continue
                if (names.none { it == field.name }) continue
                val coerced = coerce(value, field.type) ?: continue
                val written = runCatching {
                    field.isAccessible = true
                    field.set(target, coerced)
                }.isSuccess
                if (written) return true
            }
        }
        return false
    }

    /** Invokes a single-argument method by name; `false` when it does not exist or throws. */
    fun invokeSingleArg(target: Any?, name: String, value: Any?): Boolean {
        if (target == null) return false
        for (owner in hierarchyOf(target.javaClass)) {
            for (method in runCatching { owner.declaredMethods }.getOrNull().orEmpty()) {
                if (method.name != name || method.parameterTypes.size != 1) continue
                val coerced = coerce(value, method.parameterTypes[0]) ?: continue
                val invoked = runCatching {
                    method.isAccessible = true
                    method.invoke(target, coerced)
                }.isSuccess
                if (invoked) return true
            }
        }
        return false
    }

    /**
     * Invokes a static two-argument method whose parameter types accept [first] and [second].
     *
     * Used to call the host's own JSON helper (`JsonUtils.c(String, Type)`) so a synthesised DTO is
     * rebuilt by the very parser the host uses, instead of a second parser we would have to keep in
     * sync with the host's type adapters.
     */
    fun invokeStatic2(cls: Class<*>?, name: String, first: Any?, second: Any?): Any? {
        if (cls == null) return null
        for (owner in hierarchyOf(cls)) {
            for (method in runCatching { owner.declaredMethods }.getOrNull().orEmpty()) {
                if (method.name != name || method.parameterTypes.size != 2) continue
                if (!Modifier.isStatic(method.modifiers)) continue
                if (!method.parameterTypes[0].isInstance(first)) continue
                if (!method.parameterTypes[1].isInstance(second)) continue
                return runCatching {
                    method.isAccessible = true
                    method.invoke(null, first, second)
                }.getOrNull()
            }
        }
        return null
    }

    /** Invokes a two-argument instance method whose parameter types accept [first] and [second]. */
    fun invoke2(target: Any?, name: String, first: Any?, second: Any?): Any? {
        if (target == null) return null
        for (owner in hierarchyOf(target.javaClass)) {
            for (method in runCatching { owner.declaredMethods }.getOrNull().orEmpty()) {
                if (method.name != name || method.parameterTypes.size != 2) continue
                if (!method.parameterTypes[0].isInstance(first)) continue
                if (!method.parameterTypes[1].isInstance(second)) continue
                return runCatching {
                    method.isAccessible = true
                    method.invoke(target, first, second)
                }.getOrNull()
            }
        }
        return null
    }

    /**
     * Builds a fresh instance of [cls] the cheapest way that works: the no-argument constructor when the
     * host kept one, otherwise a single-argument constructor fed from [candidates] (exact type match).
     */
    fun newInstance(cls: Class<*>?, vararg candidates: Pair<Class<*>, Any?>): Any? {
        if (cls == null) return null
        runCatching { cls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance() }
            .getOrNull()
            ?.let { return it }
        for ((type, value) in candidates) {
            if (value == null || !type.isInstance(value)) continue
            val instance = runCatching {
                cls.getDeclaredConstructor(type).also { it.isAccessible = true }.newInstance(value)
            }.getOrNull()
            if (instance != null) return instance
        }
        return null
    }

    /**
     * Builds a fresh instance from an exact constructor shape, allowing `null` arguments.
     *
     * Host rows such as `androidx.preference.SeekBarPreference` only expose `(Context, AttributeSet)`
     * constructors, and the `AttributeSet` must be `null`; [newInstance] deliberately refuses null
     * candidates, so this is the shape for that case.
     */
    fun newInstanceArgs(cls: Class<*>?, vararg args: Pair<Class<*>, Any?>): Any? {
        if (cls == null) return null
        runCatching { cls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance() }
            .getOrNull()
            ?.let { return it }
        val types = args.map { it.first }.toTypedArray()
        val constructor = runCatching {
            cls.getDeclaredConstructor(*types).also { it.isAccessible = true }
        }.getOrNull() ?: return null
        return runCatching { constructor.newInstance(*args.map { it.second }.toTypedArray()) }.getOrNull()
    }

    private fun coerce(value: Any?, type: Class<*>): Any? = when {
        value == null -> null
        type.isInstance(value) -> value
        value is Number && (type == Int::class.javaPrimitiveType || type == Integer::class.java) -> value.toInt()
        value is Number && (type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java) -> value.toLong()
        value is Boolean && (type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java) -> value
        type == String::class.java -> value.toString()
        else -> null
    }

    fun hierarchyOf(cls: Class<*>): Sequence<Class<*>> =
        generateSequence(cls) { it.superclass }.takeWhile { it != Any::class.java }

    private const val HOST_PACKAGE_PREFIX = "com.oplus."
}

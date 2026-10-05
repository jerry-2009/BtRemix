package com.oplus.test

import java.util.UUID

/**
 * Loadable stand-ins for host anchor classes.
 *
 * The M6 resolver only accepts classes under `com.oplus.` / `com.coui.`, so the JVM tests need fixtures
 * in one of those packages: they play the role of a Melody class that DexKit returned.
 */

/** The "single concrete implementation" used by most tests (`UUID -> void`). */
class AlphaTarget {
    fun probe(id: UUID) {
        id.hashCode()
    }
}

/** A second class with the same shape, used to prove an ambiguous scan is treated as a miss. */
class BetaTarget {
    fun probe(id: UUID) {
        id.hashCode()
    }
}

/** A class whose method was renamed: the baseline class exists but `probe` does not. */
class RenamedTarget {
    fun renamedProbe(id: UUID) {
        id.hashCode()
    }
}

/** Abstract declarations must never be hooked. */
abstract class AbstractTarget {
    abstract fun probe(id: UUID)
}


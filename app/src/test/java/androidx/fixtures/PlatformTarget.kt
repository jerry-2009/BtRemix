package androidx.fixtures

import java.util.UUID

/**
 * Lives under a denied platform prefix, so a DexKit answer pointing here must be rejected by the resolver
 * (the denylist exists to stop a false positive aiming a hook at `androidx.*`/`java.*`).
 */
class PlatformTarget {
    fun probe(id: UUID) {
        id.hashCode()
    }
}

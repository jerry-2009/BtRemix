package com.Fusion.Btremix.melody.api

/**
 * Who may talk to [com.Fusion.Btremix.melody.bridge.IMelodyBridge] (MELODY_BRIDGE_SPEC §8).
 *
 * The service is exported but declares no `android:permission`, because `com.oplus.melody` cannot
 * request a permission owned by BtRemix. The security boundary is therefore the calling UID:
 * `Binder.getCallingUid()` cannot be forged, and the service resolves the packages owned by that UID
 * before allowing a call. This class is the pure decision function so the allow/deny matrix is covered
 * by JVM tests rather than only by a manual device check.
 *
 * BtRemix itself is allowed as well: the app process holds the same binder for instrumentation tests
 * and in-process diagnostics, and `Binder.getCallingUid()` equals our own UID for a local call.
 */
object MelodyCallPolicy {

    const val HOST_PACKAGE: String = "com.oplus.melody"

    val DEFAULT_ALLOWED_PACKAGES: Set<String> = setOf(HOST_PACKAGE)

    fun isAuthorized(
        callerPackages: Collection<String>,
        callerUid: Int,
        selfUid: Int,
        hostUid: Int? = null,
        allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,
    ): Boolean =
        callerUid == selfUid ||
            (hostUid != null && callerUid == hostUid) ||
            callerPackages.any { it in allowedPackages }
}

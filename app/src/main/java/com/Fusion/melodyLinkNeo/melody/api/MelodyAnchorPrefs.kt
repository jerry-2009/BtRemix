package com.fusion.melodyLinkNeo.melody.api

/**
 * Preference keys shared by the two sides of the M6 anchor pipeline.
 *
 * BtRemix (the module app) *writes* [REPORT] after receiving a [MelodyAnchorBroadcast]; the injected
 * `com.oplus.melody` process *reads* it through the framework's remote-preference pipe at
 * `onPackageLoaded`. The remaining keys are the app's own "have I already told the user" bookkeeping.
 */
object MelodyAnchorPrefs {

    /** Preference group both sides agree on (same file the observation/diagnostics switches use). */
    const val GROUP: String = "melody_bridge"

    /** JSON [MelodyAnchorReport] of the last resolution, keyed by the host install fingerprint. */
    const val REPORT: String = "melody_anchor_report_v1"

    /** Install fingerprint the app last told the user about (dedupe for the update prompt). */
    const val SEEN_INSTALL: String = "host_seen_install_v1"

    /** Human version last told the user about, for the "17.6.3 -> 17.7.0" line. */
    const val SEEN_VERSION: String = "host_seen_version_v1"

    /** Version a system notification was already posted for. */
    const val NOTIFIED_VERSION: String = "host_update_notified_v1"

    /**
     * Host `versionName` of the report BtRemix last received. Compared against the installed version to
     * tell "the host has not reported for this build yet" (awaiting rescan) from "already resolved".
     * The host is authoritative here: the app deliberately does not fingerprint the host APK itself.
     */
    const val REPORT_VERSION: String = "host_report_version_v1"

    /**
     * Debug override for the host install fingerprint (mirrors `host_version_override`): set it to a
     * different value to exercise the "Melody was updated" path on a real device without swapping the
     * host APK.
     */
    const val INSTALL_OVERRIDE: String = "host_install_id_override"
}

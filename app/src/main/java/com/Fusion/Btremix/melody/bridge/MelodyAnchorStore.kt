package com.Fusion.Btremix.melody.bridge

import android.content.Context
import com.Fusion.Btremix.melody.api.MelodyAnchorBroadcast
import com.Fusion.Btremix.melody.api.MelodyAnchorPrefs
import com.Fusion.Btremix.melody.api.MelodyAnchorProcessReport
import com.Fusion.Btremix.melody.api.MelodyAnchorReport

/**
 * BtRemix-side persistence for the M6 anchor report.
 *
 * The app is the only writer: the injected host can read the module's preference pipe but must not write
 * it. The value is the merged [MelodyAnchorReport] (one entry per host install fingerprint); the host
 * reads it back at `onPackageLoaded` and only reuses it when the fingerprint still matches.
 */
object MelodyAnchorStore {

    fun read(context: Context): MelodyAnchorReport? {
        val prefs = context.getSharedPreferences(MelodyAnchorPrefs.GROUP, Context.MODE_PRIVATE)
        return MelodyAnchorReport.decode(prefs.getString(MelodyAnchorPrefs.REPORT, null))
    }

    /** Merges one process report into the persisted report and returns the merged value. */
    fun record(
        context: Context,
        installId: String,
        hostPackage: String,
        version: String?,
        processReport: MelodyAnchorProcessReport,
    ): MelodyAnchorReport {
        val prefs = context.getSharedPreferences(MelodyAnchorPrefs.GROUP, Context.MODE_PRIVATE)
        val existing = MelodyAnchorReport.decode(prefs.getString(MelodyAnchorPrefs.REPORT, null))
        val merged = MelodyAnchorReport.merge(
            existing = existing,
            installId = installId,
            hostPackage = hostPackage,
            version = version,
            processReport = processReport,
        )
        prefs.edit().putString(MelodyAnchorPrefs.REPORT, MelodyAnchorReport.encode(merged)).apply()
        return merged
    }

    /**
     * Drops the persisted report so the next host start re-runs the full resolution (baseline -> DexKit
     * again). Used by the "重新扫描锚点" action; an update prompt is raised again because the tracker's
     * seen fingerprint is cleared by [MelodyHostUpdateTracker.requestRescan].
     */
    fun clear(context: Context) {
        context.getSharedPreferences(MelodyAnchorPrefs.GROUP, Context.MODE_PRIVATE)
            .edit().remove(MelodyAnchorPrefs.REPORT).apply()
    }

    /** The anchor ids that failed to resolve in [report] for the given process, if any. */
    fun missingIds(report: MelodyAnchorReport?, processName: String?): List<String> =
        report?.processes
            ?.filter { processName == null || it.processName == processName }
            .orEmpty()
            .flatMap { process -> process.misses.map { it.id } }
            .distinct()

    /** Validates one broadcast extra; exposed for the receiver test. */
    fun isTrustedHost(hostPackage: String?): Boolean = hostPackage in MelodyAnchorBroadcast.TRUSTED_HOSTS
}

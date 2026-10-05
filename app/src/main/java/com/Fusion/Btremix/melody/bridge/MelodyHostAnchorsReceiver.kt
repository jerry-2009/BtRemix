package com.Fusion.Btremix.melody.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Binder
import com.Fusion.Btremix.melody.api.MelodyAnchorBroadcast
import com.Fusion.Btremix.melody.api.MelodyAnchorPrefs

/**
 * Receives the host's anchor report (M6 §3).
 *
 * Declared `exported="true"` but always enabled (unlike [MelodyHostHelloReceiver], which is disabled
 * unless the bridge runs): the whole point is that a report arrives even when BtRemix is not running, so
 * a Melody update cannot silently degrade the panel until the user happens to open the app. The payload
 * is untrusted and therefore only ever *stored and displayed*; the injected process re-validates every
 * class name by loading it and by the `com.oplus.*` / `com.coui.*` prefix before hooking anything.
 */
class MelodyHostAnchorsReceiver : BroadcastReceiver() {

    private val log = MelodyBridgeLog()

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != MelodyAnchorBroadcast.ACTION) return
        if (!MelodyAnchorBroadcast.isCompatible(intent.getIntExtra(MelodyAnchorBroadcast.EXTRA_PROTOCOL, 0))) {
            log.event("melody.host.anchors_ignored", "reason" to "protocol")
            return
        }
        val hostPackage = intent.getStringExtra(MelodyAnchorBroadcast.EXTRA_HOST_PACKAGE)?.takeIf { it.isNotBlank() }
        val installId = intent.getStringExtra(MelodyAnchorBroadcast.EXTRA_INSTALL_ID)?.takeIf { it.isNotBlank() }
        val processName = intent.getStringExtra(MelodyAnchorBroadcast.EXTRA_PROCESS)?.takeIf { it.isNotBlank() }
        val version = intent.getStringExtra(MelodyAnchorBroadcast.EXTRA_VERSION)
        if (hostPackage == null || installId == null || processName == null ||
            !MelodyAnchorStore.isTrustedHost(hostPackage)
        ) {
            log.event("melody.host.anchors_ignored", "reason" to "payload", "host" to hostPackage)
            return
        }
        if (!senderMatches(context, hostPackage)) {
            log.event("melody.host.anchors_ignored", "reason" to "sender", "host" to hostPackage, "uid" to callingUid())
            return
        }
        val processReport = MelodyAnchorBroadcast.decodeProcess(intent.getStringExtra(MelodyAnchorBroadcast.EXTRA_ANCHORS))
            ?: run {
                log.event("melody.host.anchors_ignored", "reason" to "anchors", "host" to hostPackage)
                return
            }
        if (processReport.processName != processName) {
            log.event("melody.host.anchors_ignored", "reason" to "process_mismatch", "host" to hostPackage)
            return
        }
        runCatching {
            MelodyAnchorStore.record(
                context = context.applicationContext,
                installId = installId,
                hostPackage = hostPackage,
                version = version,
                processReport = processReport,
            )
            if (version != null) {
                context.applicationContext
                    .getSharedPreferences(MelodyAnchorPrefs.GROUP, Context.MODE_PRIVATE)
                    .edit()
                    .putString(MelodyAnchorPrefs.REPORT_VERSION, version)
                    .apply()
            }
        }.onFailure {
            log.warn("melody.host.anchors_store_failed", it)
            return
        }
        log.event(
            "melody.host.anchors_received",
            "host" to hostPackage,
            "process" to processName,
            "hits" to processReport.hits,
            "total" to processReport.total,
            "misses" to processReport.misses.joinToString(",") { it.id },
        )
        MelodyHostUpdateTracker.onAnchorReport(context.applicationContext)
    }

    /**
     * Best-effort sender check. API 34 exposes `getSendingUid()`; before that the uid ownership check is
     * skipped and the payload is trusted only because it is never executed - the host re-validates.
     */
    private fun senderMatches(context: Context, hostPackage: String): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val uid = runCatching { sentFromUid }.getOrDefault(-1)
        if (uid <= 0) return true
        val packages = runCatching { context.packageManager.getPackagesForUid(uid) }.getOrNull() ?: return true
        // The host package is the expected sender; our own uid is accepted too (a self-sent report only
        // writes our own preference file and cannot escalate anything, and it keeps the path testable).
        return packages.contains(hostPackage) || packages.contains(context.packageName)
    }

    private fun callingUid(): Int = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
}

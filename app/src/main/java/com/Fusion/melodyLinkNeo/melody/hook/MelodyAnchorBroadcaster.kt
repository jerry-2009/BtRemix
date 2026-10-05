package com.fusion.melodyLinkNeo.melody.hook

import android.app.Application
import android.content.Context
import android.content.Intent
import com.fusion.melodyLinkNeo.melody.api.MelodyAnchorBroadcast
import com.fusion.melodyLinkNeo.melody.api.MelodyAnchorProcessReport
import io.github.libxposed.api.XposedInterface

/**
 * Host -> BtRemix delivery of one process' anchor report (M6 §3).
 *
 * The report is produced during `onPackageLoaded`, but sending a broadcast needs a `Context`, and the
 * host's `Application` does not exist yet at that point. So the report is parked here and the actual
 * `sendBroadcast` runs from the first `Application.onCreate` (the same framework hook the M5.1 card menu
 * already uses), which is a few milliseconds later and still before any UI reads the panel.
 *
 * The broadcast is explicit (`setPackage("com.fusion.melodyLinkNeo")`) and carries only anchor class names plus
 * the install fingerprint - never device or session data. BtRemix re-validates everything before use.
 */
internal class MelodyAnchorBroadcaster(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val report: MelodyAnchorProcessReport,
    private val installId: String,
    private val hostPackage: String,
) {

    @Volatile
    private var sent = false

    fun install() {
        val onCreate = runCatching { Application::class.java.getMethod("onCreate") }.getOrNull() ?: run {
            log.event("melody.host.anchors_send_skipped", "reason" to "no_oncreate")
            return
        }
        module.hook(onCreate).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            runCatching { send(chain.thisObject as? Context) }
                .onFailure { log.warn("melody.host.anchors_send_failed", it) }
            result
        })
    }

    private fun send(context: Context?) {
        if (sent || context == null) return
        sent = true
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val intent = Intent(MelodyAnchorBroadcast.ACTION)
            .setPackage(BTREMIX_PACKAGE)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(MelodyAnchorBroadcast.EXTRA_PROTOCOL, MelodyAnchorBroadcast.PROTOCOL)
            .putExtra(MelodyAnchorBroadcast.EXTRA_INSTALL_ID, installId)
            .putExtra(MelodyAnchorBroadcast.EXTRA_HOST_PACKAGE, hostPackage)
            .putExtra(MelodyAnchorBroadcast.EXTRA_PROCESS, report.processName)
            .putExtra(MelodyAnchorBroadcast.EXTRA_VERSION, version)
            .putExtra(MelodyAnchorBroadcast.EXTRA_ANCHORS, MelodyAnchorBroadcast.encodeProcess(report))
        val delivered = runCatching { context.sendBroadcast(intent) }
        if (delivered.isSuccess) {
            log.event(
                "melody.host.anchors_sent",
                "process" to report.processName,
                "hits" to report.hits,
                "total" to report.total,
                "version" to (version ?: "?"),
            )
        } else {
            log.warn("melody.host.anchors_send_failed", delivered.exceptionOrNull())
        }
    }

    private companion object {
        const val BTREMIX_PACKAGE = "com.fusion.melodyLinkNeo"
    }
}

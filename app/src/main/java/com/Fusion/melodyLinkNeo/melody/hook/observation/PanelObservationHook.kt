package com.fusion.melodyLinkNeo.melody.hook.observation

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.fusion.melodyLinkNeo.melody.api.PanelKeyCatalog
import com.fusion.melodyLinkNeo.melody.api.PanelKeyChange
import com.fusion.melodyLinkNeo.melody.api.PanelKeyRow
import com.fusion.melodyLinkNeo.melody.hook.DumpSink
import com.fusion.melodyLinkNeo.melody.hook.MelodyLog
import com.fusion.melodyLinkNeo.melody.hook.PreferenceTree
import com.fusion.melodyLinkNeo.melody.hook.Reflect
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * M1 read-only observation of the Melody panels (MELODY_BRIDGE_SPEC §7.1/§7.2, §12 M1).
 *
 * Three layers, exactly as the spec orders them by stability:
 *  1. the two manifest-declared Activities (`DetailMainActivity`, `OneSpaceDetailActivity`) — their
 *     `onResume` (fallback `onStart`) is a stable, R8-proof entry point;
 *  2. the known PreferenceFragment base classes — the fast path that reaches the screen as soon as
 *     `onViewCreated` returns;
 *  3. `Application.ActivityLifecycleCallbacks` — a version-independent fallback that scans resumed
 *     activities for Preference-Fragment-shaped instances even when every fragment class was renamed.
 *
 * Dumps are retried for a few seconds because panels fill their list asynchronously (the host can
 * wait on a network whitelist round-trip), and the resulting rows feed both the `melody.panel.row`
 * log stream and the Markdown/JSON dump written by [DumpSink].
 */
internal class PanelObservationHook(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    private val catalog = PanelKeyCatalog()
    private val sink = DumpSink(log)
    private val handler = Handler(Looper.getMainLooper())
    private val lastSignatures = HashMap<String, String>()
    private var appContext: Context? = null
    private var hostVersion: String? = null
    private var writeScheduled = false

    fun install() {
        hookApplicationOnCreate()
        hookActivities()
        hookPreferenceFragments()
    }

    // --- layer 3: application-level context and lifecycle scan ------------------------------------

    private fun hookApplicationOnCreate() {
        val onCreate = runCatching { Application::class.java.getMethod("onCreate") }.getOrNull()
        if (onCreate == null) {
            log.event("melody.anchor.missing", "hook" to "panel.application")
            return
        }
        module.hook(onCreate).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            runCatching { bootstrap(chain.thisObject as? Application) }
                .onFailure { log.warn("melody.panel.bootstrap_failed", it) }
            result
        })
        log.event("melody.anchor.hooked", "hook" to "panel.application")
    }

    private fun bootstrap(app: Application?) {
        if (app == null) return
        appContext = app.applicationContext ?: app
        hostVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull()
        log.event(
            "melody.panel.context_ready",
            "package" to app.packageName,
            "version" to hostVersion,
            "process" to app.applicationInfo?.processName,
        )
        runCatching { app.registerActivityLifecycleCallbacks(ScanCallbacks()) }
            .onSuccess { log.event("melody.panel.scan_registered") }
            .onFailure { log.warn("melody.panel.scan_register_failed", it) }
    }

    private inner class ScanCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            if (!isRelevantActivity(activity.javaClass.name)) return
            // The panel lives in a PreferenceFragment; when the FragmentManager is (unusually) empty
            // fall back to probing the Activity itself so the screen is never silently skipped.
            val fragments = runCatching { PreferenceTree.collectFragments(activity) }.getOrDefault(emptyList())
            if (fragments.isEmpty()) {
                scheduleDump(WeakReference<Any>(activity), attempt = 0, reason = "activity.scan")
            } else {
                for (fragment in fragments) {
                    scheduleDump(WeakReference<Any>(fragment), attempt = 0, reason = "fragment.scan")
                }
            }
        }

        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    // --- layer 1: manifest-declared activities ----------------------------------------------------

    private fun hookActivities() {
        for (className in ACTIVITY_CLASSES) {
            val cls = Reflect.loadClass(className, loader)
            if (cls == null) {
                log.event("melody.anchor.missing", "hook" to "panel.activity", "class" to className)
                continue
            }
            val method = Reflect.findHostDeclaredMethod(cls, "onResume")
                ?: Reflect.findHostDeclaredMethod(cls, "onStart")
            if (method == null) {
                log.event("melody.anchor.missing", "hook" to "panel.activity", "class" to className)
                continue
            }
            module.hook(method).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                runCatching {
                    scheduleDump(WeakReference<Any>(chain.thisObject), attempt = 0, reason = "activity." + method.name)
                }
                result
            })
            log.event("melody.anchor.hooked", "hook" to "panel.activity", "class" to className, "method" to method.name)
        }
    }

    // --- layer 2: known PreferenceFragment bases --------------------------------------------------

    private fun hookPreferenceFragments() {
        val hooked = HashSet<Method>()
        for (className in FRAGMENT_CLASSES) {
            val cls = Reflect.loadClass(className, loader)
            if (cls == null) continue
            val method = PreferenceTree.findOnViewCreated(cls) ?: continue
            if (!hooked.add(method)) continue
            module.hook(method).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                runCatching {
                    scheduleDump(WeakReference<Any>(chain.thisObject), attempt = 0, reason = "fragment.onViewCreated")
                }
                result
            })
            log.event(
                "melody.anchor.hooked",
                "hook" to "panel.fragment",
                "class" to className,
                "declaredBy" to method.declaringClass.name,
            )
        }
    }

    // --- dump scheduling --------------------------------------------------------------------------

    private fun scheduleDump(ref: WeakReference<Any>, attempt: Int, reason: String) {
        handler.postDelayed({
            runCatching { dumpTick(ref, attempt, reason) }
                .onFailure { log.warn("melody.panel.tick_failed", it) }
        }, if (attempt == 0) FIRST_DELAY_MS else RETRY_DELAY_MS)
    }

    /** One retry tick; all of its work is exception-guarded so a posted failure never kills the host. */
    private fun dumpTick(ref: WeakReference<Any>, attempt: Int, reason: String) {
        val host = ref.get() ?: return
        if (!isRelevantHost(host)) return
        val screen = runCatching { PreferenceTree.findScreen(host) }.getOrNull()
        if (screen == null) {
            if (attempt < MAX_MISSING_ATTEMPTS) {
                scheduleDump(ref, attempt + 1, reason)
            } else if (attempt == MAX_MISSING_ATTEMPTS) {
                log.event("melody.panel.screen_missing", "host" to host.javaClass.name, "reason" to reason)
            }
            return
        }
        val changed = runCatching { dumpScreen(screen, screenLabel(host), reason) }
            .getOrElse {
                log.warn("melody.panel.dump_failed", it)
                false
            }
        if (changed && attempt < MAX_REFRESH_ATTEMPTS) scheduleDump(ref, attempt + 1, reason)
    }

    private fun dumpScreen(screen: Any, screenId: String, reason: String): Boolean {
        val rows = PreferenceTree.dump(screen, screenId)
        for (row in rows) {
            when (catalog.record(row)) {
                PanelKeyChange.ADDED -> row("new", row)
                PanelKeyChange.UPDATED -> row("updated", row)
                PanelKeyChange.UNCHANGED -> Unit
            }
        }
        val signature = rows.joinToString(";") {
            it.key + '|' + it.className + '|' + it.visible + '|' + it.enabled + '|' + it.order
        }
        if (lastSignatures[screenId] == signature) return false
        lastSignatures[screenId] = signature
        log.event(
            "melody.panel.snapshot",
            "screen" to screenId,
            "rows" to rows.size,
            "visible" to rows.count { it.visible },
            "reason" to reason,
        )
        scheduleWrite()
        return true
    }

    private fun row(state: String, row: PanelKeyRow) {
        log.event(
            "melody.panel.row",
            "state" to state,
            "screen" to row.screen,
            "key" to row.key,
            "title" to row.title,
            "class" to row.className,
            "visible" to row.visible,
            "enabled" to row.enabled,
            "order" to row.order,
            "depth" to row.depth,
        )
    }

    private fun scheduleWrite() {
        if (writeScheduled) return
        writeScheduled = true
        handler.postDelayed({
            writeScheduled = false
            val context = appContext ?: return@postDelayed
            runCatching { sink.write(context, catalog, hostVersion) }
                .onFailure { log.warn("melody.panel.dump_failed", it) }
        }, WRITE_DEBOUNCE_MS)
    }

    // --- host classification ----------------------------------------------------------------------

    private fun isRelevantActivity(className: String): Boolean = className in ACTIVITY_CLASSES

    private fun isRelevantHost(host: Any): Boolean {
        if (host is Activity) return isRelevantActivity(host.javaClass.name)
        val activity = Reflect.call(host, "getActivity") ?: Reflect.call(host, "requireActivity")
        val activityName = activity?.javaClass?.name
        if (activityName != null && isRelevantActivity(activityName)) return true
        val hostName = host.javaClass.name
        return hostName.startsWith("com.oplus.melody.onespace.")
    }

    private fun screenLabel(host: Any): String {
        val activityName = when (host) {
            is Activity -> host.javaClass.name
            else -> {
                val activity = Reflect.call(host, "getActivity") ?: Reflect.call(host, "requireActivity")
                activity?.javaClass?.name
            }
        }
        val simple = activityName?.substringAfterLast('.') ?: host.javaClass.simpleName
        return simple
    }

    private companion object {
        val ACTIVITY_CLASSES = listOf(
            "com.oplus.melody.ui.component.detail.DetailMainActivity",
            "com.oplus.melody.onespace.OneSpaceDetailActivity",
        )

        /**
         * Preference fragment bases observed across Melody builds. Names are probed individually and
         * a missing one is not an error: the Activity lifecycle scan covers renamed replacements.
         */
        val FRAGMENT_CLASSES = listOf(
            "com.oplus.melody.ui.base.c",
            "com.oplus.melody.ui.base.b",
            "com.coui.appcompat.preference.h",
            "androidx.preference.g",
        )

        const val FIRST_DELAY_MS = 250L
        const val RETRY_DELAY_MS = 1200L
        const val WRITE_DEBOUNCE_MS = 1500L
        const val MAX_REFRESH_ATTEMPTS = 12
        const val MAX_MISSING_ATTEMPTS = 6
    }
}

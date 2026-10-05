package com.Fusion.Btremix.melody.hook.injection

import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.Fusion.Btremix.BuildConfig
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.api.MelodyBridgeResult
import com.Fusion.Btremix.melody.api.MelodyPanelApplyResult
import com.Fusion.Btremix.melody.api.MelodyPanelPolicy
import com.Fusion.Btremix.melody.api.PanelRow
import com.Fusion.Btremix.melody.api.PanelVisibilityApplier
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.PreferenceTree
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClient
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M4.2 official-row hide/grey injection on the Melody detail page
 * (HANDOFF_MELODY_M4_PLAN.md §3 M4.2, MELODY_BRIDGE_SPEC §7.1/§7.2, decisions D4/D5).
 *
 * It reuses exactly the three anchors M1's read-only [PanelObservationHook] proved stable, because the
 * panel only becomes addressable *after* the host finishes building it:
 *  1. the manifest-declared Activities' `onResume` (fallback `onStart`);
 *  2. the known PreferenceFragment bases' `onViewCreated`;
 *  3. `Application.ActivityLifecycleCallbacks` as the rename-proof scan.
 *
 * Rules are applied in the spec's fixed order (sections -> rows -> grey -> empty cleanup) by the pure
 * [PanelVisibilityApplier]. Everything is fail-open: no bridge/link, no managed MAC, no envelope or a
 * degraded `panel` node all mean "leave the official panel exactly as it is" and at most one log line.
 * Writes go through `setVisible(false)` / `setEnabled(false)` only - the list is never mutated, because
 * the host rebuilds it on refresh and a removed `Preference` would simply come back.
 *
 * The run inside the host is not a single pass. The visible groups are built by
 * `DetailMainViewManager` (`G9.d0`) on background tasks once the whitelist LiveData answers - measured
 * on the device as arriving well after `onViewCreated` - so while a relevant page is resumed the hook
 * re-applies every second (idempotent writes, top-level rows only). A short retry window was tried
 * first and was not enough: it finished before the rows existed.
 *
 * Nothing here knows about Definitions or BLE: the policy travels in the projection envelope and is
 * read back through the same cache the provider path uses, so the panel and the provider always agree.
 */
internal class MelodyPanelInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    /**
     * Resumed relevant Activities (identity set). The host fills the detail list asynchronously - the
     * group model is posted from `DetailMainViewManager`'s background tasks (`G9.a0`/`G9.c0`) once the
     * whitelist LiveData answers - so a single pass (or a short retry window) can run before the rows
     * exist. While a relevant page is resumed we re-apply once per [POLL_INTERVAL_MS] instead.
     */
    private val resumedLock = Any()
    private val resumed = IdentityHashMap<Activity, Boolean>()
    private var polling = false
    private var pollTicks = 0

    /** Deduplicates the "nothing to do" diagnostics across the several anchors that can fire for one page. */
    private val lastSkipReason = HashMap<String, String>()

    /** Deduplicates the "what policy is in force" diagnostic per screen + device + policy contents. */
    private val lastPolicyFingerprint = HashMap<String, String>()

    /** Deduplicates the "what does the screen contain" diagnostic, so a late fill is visible in logcat. */
    private val lastTopKeys = HashMap<String, String>()

    /** Host classes already reported as having no PreferenceScreen (one line each, not one per tick). */
    private val reportedScreenMissing = HashSet<String>()

    /** The M4.4 host-native picker (COUI bottom sheet), built lazily with the host class loader. */
    private val picker: MelodyHostPicker by lazy {
        MelodyHostPicker(loader, MelodyGroupLog { name, fields -> log.event(name, *fields.toTypedArray()) })
    }

    /** One debounced snapshot-driven re-apply is pending; set on the binder thread, cleared on main. */
    private val snapshotRefreshPending = AtomicBoolean(false)

    /** The client's snapshot listener is registered at most once per host process. */
    private var snapshotListenerRegistered = false

    /**
     * Host objects (fragment or activity) that resolved a `PreferenceScreen` at least once.
     *
     * The lifecycle rescans (`collectFragments`) do not always reach the fragment that owns the
     * detail panel - a renamed nested manager or a differently-hosted page makes them return empty,
     * and the activity host then has no screen of its own. The objects the anchor hooks fired on are
     * exactly the ones that built the panel, so they are kept (weakly) and re-applied while their
     * activity is still resumed. This is what makes a later value change reach the panel (M4.4).
     */
    private val liveHosts = Collections.synchronizedList(ArrayList<WeakReference<Any>>())

    private val poll = object : Runnable {
        override fun run() {
            val activities = synchronized(resumedLock) { resumed.keys.toList() }
            if (activities.isEmpty()) {
                polling = false
                return
            }
            // Debug builds also emit an unconditional row dump every HEARTBEAT_TICKS, so a logcat
            // capture proves both "the poll is alive" and "what the screen contains right now" without
            // guessing. Release stays quiet: the change-triggered `melody.panel.rows` is enough there.
            val heartbeat = BuildConfig.DEBUG && pollTicks % HEARTBEAT_TICKS == 0
            runCatching { applyResumed(activities, heartbeat) }
                .onFailure { log.warn("melody.panel.poll_failed", it) }
            pollTicks++
            // Fast phase while the host is filling the page, then a slow idle backstop for as long as
            // the page stays resumed: a write that succeeds but whose push we miss (M4.4) still shows
            // up within a few seconds instead of never.
            val interval = if (pollTicks >= MAX_POLL_TICKS) IDLE_POLL_INTERVAL_MS else POLL_INTERVAL_MS
            handler.postDelayed(this, interval)
        }
    }

    fun install() {
        val application = hookApplicationOnCreate()
        val activities = hookActivities()
        val fragments = hookPreferenceFragments()
        val model = hookGroupModel()
        if (!application && !activities && !fragments && !model) {
            log.event("melody.anchor.missing", "hook" to "inject.panel")
        }
    }

    // --- layer 3: application context and lifecycle scan ------------------------------------------

    private fun hookApplicationOnCreate(): Boolean {
        val onCreate = runCatching { Application::class.java.getMethod("onCreate") }.getOrNull()
        if (onCreate == null) {
            log.event("melody.anchor.missing", "hook" to "inject.panel.application")
            return false
        }
        module.hook(onCreate).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            runCatching { bootstrap(chain.thisObject as? Application) }
                .onFailure { log.warn("melody.panel.inject_bootstrap_failed", it) }
            result
        })
        log.event("melody.anchor.hooked", "hook" to "inject.panel.application")
        return true
    }

    private fun bootstrap(app: Application?) {
        if (app == null) return
        appContext = app.applicationContext ?: app
        runCatching { app.registerActivityLifecycleCallbacks(ScanCallbacks()) }
            .onSuccess { log.event("melody.panel.inject_scan_registered") }
            .onFailure { log.warn("melody.panel.inject_scan_register_failed", it) }
    }

    private inner class ScanCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            if (!isRelevantActivity(activity.javaClass.name)) return
            // M5.4b: the page is up. If the bridge is not attached yet (cold process, or a dropped
            // link), ask BtRemix for a doorbell now instead of waiting for the next keepalive tick.
            MelodyBridgeClients.existing()?.requestLink("panel_screen")
            synchronized(resumedLock) { resumed[activity] = true }
            startPolling()
            // One fast pass right after the page is up; the poll then covers the asynchronous fill.
            scheduleApplyOnce(WeakReference<Any>(activity), reason = "activity.onResume")
            val fragments = runCatching { PreferenceTree.collectFragments(activity) }.getOrDefault(emptyList())
            for (fragment in fragments) {
                scheduleApplyOnce(WeakReference<Any>(fragment), reason = "fragment.scan")
            }
        }

        override fun onActivityPaused(activity: Activity) = forget(activity)

        override fun onActivityStopped(activity: Activity) = forget(activity)

        override fun onActivityDestroyed(activity: Activity) = forget(activity)

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    private fun forget(activity: Activity) {
        val empty = synchronized(resumedLock) {
            resumed.remove(activity)
            resumed.isEmpty()
        }
        if (empty) polling = false
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        pollTicks = 0
        handler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    private fun applyResumed(activities: List<Activity>, heartbeat: Boolean) {
        val applied = IdentityHashMap<Any, Boolean>()
        var hosts = 0
        for (activity in activities) {
            val fragments = runCatching { PreferenceTree.collectFragments(activity) }.getOrDefault(emptyList())
            if (fragments.isEmpty()) {
                hosts++
                applyHost(activity, "poll", heartbeat)
                applied[activity] = true
            } else {
                for (fragment in fragments) {
                    hosts++
                    applyHost(fragment, "poll", heartbeat)
                    applied[fragment] = true
                }
            }
        }
        // Re-apply the hosts that actually resolved a screen before; the fragment scan can miss them.
        for (reference in synchronized(liveHosts) { liveHosts.toList() }) {
            val host = reference.get() ?: continue
            if (applied.containsKey(host)) continue
            val activity = activityOf(host) ?: continue
            if (activities.none { it === activity }) continue
            hosts++
            applyHost(host, "poll", heartbeat)
        }
        if (heartbeat) {
            log.event("melody.panel.poll", "activities" to activities.size, "hosts" to hosts, "tick" to pollTicks)
        }
    }

    // --- layer 1: manifest-declared activities ----------------------------------------------------

    private fun hookActivities(): Boolean {
        var hooked = false
        for (className in ACTIVITY_CLASSES) {
            val cls = Reflect.loadClass(className, loader)
            if (cls == null) {
                log.event("melody.anchor.missing", "hook" to "inject.panel.activity", "class" to className)
                continue
            }
            val method = Reflect.findHostDeclaredMethod(cls, "onResume")
                ?: Reflect.findHostDeclaredMethod(cls, "onStart")
            if (method == null) {
                log.event("melody.anchor.missing", "hook" to "inject.panel.activity", "class" to className)
                continue
            }
            module.hook(method).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                runCatching { scheduleApplyOnce(WeakReference<Any>(chain.thisObject), "activity." + method.name) }
                result
            })
            log.event("melody.anchor.hooked", "hook" to "inject.panel.activity", "class" to className, "method" to method.name)
            hooked = true
        }
        return hooked
    }

    // --- layer 2: known PreferenceFragment bases --------------------------------------------------

    private fun hookPreferenceFragments(): Boolean {
        val hooked = HashSet<Method>()
        for (className in FRAGMENT_CLASSES) {
            val cls = Reflect.loadClass(className, loader) ?: continue
            val method = PreferenceTree.findOnViewCreated(cls) ?: continue
            if (!hooked.add(method)) continue
            module.hook(method).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                runCatching { scheduleApplyOnce(WeakReference<Any>(chain.thisObject), "fragment.onViewCreated") }
                result
            })
            log.event(
                "melody.anchor.hooked",
                "hook" to "inject.panel.fragment",
                "class" to className,
                "declaredBy" to method.declaringClass.name,
            )
        }
        if (hooked.isEmpty()) {
            log.event("melody.anchor.missing", "hook" to "inject.panel.fragment")
        }
        return hooked.isNotEmpty()
    }

    // --- pre-emptive filter on the host's group model -------------------------------------------

    /**
     * `DetailMainViewManager` (`G9.d0`) builds the whole detail page as a `Map<groupKey, List<itemName>>`
     * and posts it through `MelodyLiveData` (`y8.k`); `G9.H.onViewCreated` observes that LiveData with an
     * `A9.f` callback, which is what materialises the group `Preference`s. Removing a group key from the
     * map *before* the callback runs means the official group is never built at all - no reliance on the
     * panel being walked after the fact.
     *
     * This is the second, independent mechanism of M4.2: the `PreferenceScreen` pass handles rows that
     * already exist, this handles rows that are about to be created. Both are fail-open.
     */
    private fun hookGroupModel(): Boolean {
        val cls = Reflect.loadClass(GROUP_OBSERVER_CLASS, loader)
        if (cls == null) {
            log.event("melody.anchor.missing", "hook" to "inject.panel.model", "class" to GROUP_OBSERVER_CLASS)
            return false
        }
        val method = Reflect.findMethod(cls, "onChanged", arrayOf(Any::class.java))
        if (method == null) {
            log.event("melody.anchor.missing", "hook" to "inject.panel.model", "class" to GROUP_OBSERVER_CLASS)
            return false
        }
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            runCatching { filterGroupModel(chain) }
                .onFailure { log.warn("melody.panel.model_filter_failed", it) }
            chain.proceed()
        })
        log.event("melody.anchor.hooked", "hook" to "inject.panel.model", "class" to cls.name, "method" to method.name)
        return true
    }

    /**
     * The model is the one map whose values are all `List<String>` (group key -> item class names, e.g.
     * `earphone -> [MoreSettingItem, firmwareVersion]`); every other `onChanged` argument is ignored.
     */
    private fun filterGroupModel(chain: XposedInterface.Chain) {
        val model = chain.args.getOrNull(0) as? MutableMap<Any?, Any?> ?: return
        if (model.isEmpty() || !model.values.all(::isItemNameList)) return
        val captured = readCaptured(chain.thisObject) ?: return
        val client = MelodyBridgeClients.existing() ?: return
        val managed = runCatching { client.managedMacsFast() }.getOrDefault(emptyList()).toSet()
        if (managed.isEmpty()) return
        val mac = resolveManagedMac(captured, managed) ?: return
        val policy = client.panelFast(mac) ?: return
        if (!policy.present) return

        var removed = 0
        for (key in policy.hideSections) {
            if (model.remove(key) != null) {
                removed++
                log.event("melody.panel.model_hide", "mac" to mac, "key" to key)
            }
        }
        if (removed > 0) log.event("melody.panel.model_hidden", "mac" to mac, "count" to removed)
    }

    private fun isItemNameList(value: Any?): Boolean =
        value is List<*> && value.all { it is String }

    /**
     * `A9.f` captures its target fragment in its synthetic `b` field; [Reflect.readField] deliberately
     * skips synthetic fields, so this is the same targeted read the whitelist-repository hook uses.
     */
    private fun readCaptured(callback: Any?): Any? {
        var type: Class<*>? = callback?.javaClass
        while (type != null && type != Any::class.java) {
            val field = runCatching { type.getDeclaredField(CAPTURE_FIELD) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(callback)
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    // --- apply scheduling -------------------------------------------------------------------------

    /** One deferred apply for an anchor; the poll keeps re-applying while the page stays resumed. */
    private fun scheduleApplyOnce(ref: WeakReference<Any>, reason: String) {
        handler.postDelayed({
            runCatching { ref.get()?.let { applyHost(it, reason) } }
                .onFailure { log.warn("melody.panel.inject_tick_failed", it) }
        }, FIRST_DELAY_MS)
    }

    /** Resolves the screen for one host and applies the policy; every step is exception-guarded. */
    private fun applyHost(host: Any, reason: String, forceLog: Boolean = false) {
        if (!isRelevantHost(host)) return
        val screen = runCatching { PreferenceTree.findScreen(host) }.getOrNull()
        if (screen == null) {
            if (reportedScreenMissing.add(host.javaClass.name)) {
                log.event("melody.panel.screen_missing", "host" to host.javaClass.name, "reason" to reason)
            }
            return
        }
        rememberHost(host)
        runCatching { applyScreen(screen, host, screenLabel(host), reason, forceLog) }
            .getOrElse {
                log.warn("melody.panel.apply_failed", it)
            }
    }

    /** Keeps a weak reference to a host that resolved a screen, pruning dead/duplicate entries. */
    private fun rememberHost(host: Any) {
        synchronized(liveHosts) {
            liveHosts.removeAll { it.get() == null }
            if (liveHosts.none { it.get() === host }) liveHosts.add(WeakReference(host))
        }
    }

    /**
     * Applies the policy for the page's device. Re-applied on every poll tick; the applier writes only
     * rows whose live value still differs, so repeated calls are free once the panel is stable.
     */
    private fun applyScreen(screen: Any, host: Any, screenId: String, reason: String, forceLog: Boolean = false) {
        val roots = runCatching { MelodyPanelAdapter.rowsOf(screen) }.getOrDefault(emptyList())
        logRowsOnce(screenId, roots, forceLog)

        val client = MelodyBridgeClients.existing() ?: run {
            noteSkip(screenId, "client")
            return
        }
        val managed = runCatching { client.managedMacsFast() }.getOrDefault(emptyList()).toSet()
        if (managed.isEmpty()) {
            noteSkip(screenId, "no_managed")
            return
        }

        val mac = resolveManagedMac(host, managed) ?: run {
            noteSkip(screenId, "mac")
            return
        }
        val policy = client.panelFast(mac) ?: run {
            noteSkip(screenId, "envelope")
            return
        }
        if (!policy.present) {
            noteSkip(screenId, "policy:" + policy.missingReason)
            log.event(
                "melody.panel.policy_missing",
                "screen" to screenId,
                "mac" to mac,
                "reason" to policy.missingReason,
            )
            return
        }
        clearSkip(screenId)
        logPolicyOnce(screenId, mac, policy)
        ensureSnapshotListener(client)
        if (forceLog) {
            // Heartbeat: prove the re-apply reaches this screen and show what the rows read from the
            // snapshot cache (M4.4 on-device diagnosis).
            val readings = policy.group?.rows?.joinToString(",") { row ->
                row.key + "=" + (row.state?.let { state -> client.stateTextFast(mac, state) } ?: "-")
            }
            log.event(
                "melody.panel.read",
                "screen" to screenId,
                "mac" to mac,
                "reason" to reason,
                "roots" to roots.size,
                "rows" to readings,
            )
        }

        val result = PanelVisibilityApplier.apply(policy, roots)
        if (!result.isEmpty) logApplied(screenId, mac, reason, result)

        // M4.3c: keep the self-built「高级功能」card in sync (create after `sound`, re-fill the live
        // values, hide it when the policy no longer carries a group). Idempotent and fail-open.
        runCatching {
            MelodyPanelGroupApplier.apply(
                screenId,
                screen,
                appContext,
                policy,
                MelodyRowStateText { state -> client.stateTextFast(mac, state) },
                mac,
                loader,
                MelodyGroupLog { name, fields -> log.event(name, *fields.toTypedArray()) },
                clickBinder = MelodyPanelActionBinder.binder(
                    mac = mac,
                    // Spec §7.4: the click re-checks the page's device before it writes.
                    currentMac = { resolveManagedMac(host, managed) },
                    stateText = MelodyRowStateText { state -> client.stateTextFast(mac, state) },
                    executor = MelodyPanelExecutor { action, args, onResult ->
                        client.executeAsync(mac, action, args) { code ->
                            onResult(code)
                            // A successful write is pushed by BtRemix, but the panel must not hinge on
                            // that push reaching this process: pull once so the row refreshes even if
                            // the listener was momentarily unattached (M4.4 consistency).
                            if (code == MelodyBridgeResult.OK) client.refreshSnapshotAsync(mac)
                        }
                    },
                    choicePresenter = picker,
                    sliderPresenter = picker,
                    loader = loader,
                    log = MelodyGroupLog { name, fields -> log.event(name, *fields.toTypedArray()) },
                ),
            )
        }.onFailure { log.warn("melody.panel.group_failed", it) }
    }

    /**
     * M4.4 consistency link: react to a pushed snapshot instead of waiting for the next 1 Hz tick, so a
     * value the Compose page (or the device) changed after the initial fill still reaches the panel.
     * The push arrives on a binder thread, so it only sets a flag and posts one coalesced re-apply.
     */
    private fun ensureSnapshotListener(client: MelodyBridgeClient) {
        if (snapshotListenerRegistered) return
        snapshotListenerRegistered = true
        client.addSnapshotListener { mac ->
            if (!snapshotRefreshPending.compareAndSet(false, true)) return@addSnapshotListener
            if (BuildConfig.DEBUG) {
                log.event("melody.panel.refresh", "mac" to mac, "reason" to "snapshot")
            }
            handler.postDelayed({
                snapshotRefreshPending.set(false)
                val activities = synchronized(resumedLock) { resumed.keys.toList() }
                if (activities.isEmpty()) return@postDelayed
                runCatching { applyResumed(activities, heartbeat = false) }
                    .onFailure { log.warn("melody.panel.snapshot_refresh_failed", it) }
            }, SNAPSHOT_DEBOUNCE_MS)
        }
        log.event("melody.panel.snapshot_listener")
    }

    /** Logs the screen's top-level keys whenever they change - this is what proves a late panel fill. */
    private fun logRowsOnce(screenId: String, roots: List<PanelRow>, force: Boolean = false) {
        val keys = roots.joinToString(",") { it.key }
        if (!force && lastTopKeys[screenId] == keys) return
        lastTopKeys[screenId] = keys
        log.event("melody.panel.rows", "screen" to screenId, "count" to roots.size, "keys" to keys)
    }

    /** One line per screen/device/policy so a logcat capture names the policy the panel is judged against. */
    private fun logPolicyOnce(screenId: String, mac: String, policy: MelodyPanelPolicy) {
        val fingerprint = mac + '|' + policy.hideSections + '|' + policy.hideKeys + '|' + policy.greyKeys
        if (lastPolicyFingerprint[screenId] == fingerprint) return
        lastPolicyFingerprint[screenId] = fingerprint
        log.event(
            "melody.panel.policy",
            "screen" to screenId,
            "mac" to mac,
            "sections" to policy.hideSections.sorted().joinToString(","),
            "keys" to policy.hideKeys.size,
            "grey" to policy.greyKeys.size,
        )
    }

    private fun logApplied(screenId: String, mac: String, reason: String, result: MelodyPanelApplyResult) {
        for (key in result.hiddenSections) log.event("melody.panel.hide", "screen" to screenId, "section" to key)
        for (key in result.hiddenKeys) log.event("melody.panel.hide", "screen" to screenId, "key" to key)
        for (key in result.greyedKeys) log.event("melody.panel.grey", "screen" to screenId, "key" to key)
        for (key in result.hiddenEmptySections) {
            log.event("melody.panel.hide", "screen" to screenId, "section" to key, "reason" to "empty")
        }
        log.event(
            "melody.panel.applied",
            "screen" to screenId,
            "mac" to mac,
            "reason" to reason,
            "changed" to result.changeCount,
        )
    }

    private fun noteSkip(screenId: String, reason: String) {
        if (lastSkipReason[screenId] == reason) return
        lastSkipReason[screenId] = reason
        log.event("melody.panel.policy_absent", "screen" to screenId, "reason" to reason)
    }

    private fun clearSkip(screenId: String) {
        lastSkipReason.remove(screenId)
    }

    // --- MAC resolution ---------------------------------------------------------------------------

    /**
     * Which managed device this page is about. The host's own deep link carries `device_mac_info`
     * (that is what the device-centre card and the documented `am start` both pass), and the Settings
     * path can omit it, so the bound fragment/activity objects are scanned as a fallback. Every
     * candidate is filtered against the managed set, which is what keeps the injection off official
     * (non-managed) devices: a page we cannot positively identify is left untouched.
     */
    private fun resolveManagedMac(host: Any, managed: Set<String>): String? {
        val activity = activityOf(host)
        activity?.intent?.let { intent ->
            macFromIntent(intent, managed)?.let { return it }
        }
        findManagedMac(host, MAX_MAC_SCAN_DEPTH, managed, IdentityHashMap())?.let { return it }
        if (activity != null && activity !== host) {
            findManagedMac(activity, MAX_MAC_SCAN_DEPTH, managed, IdentityHashMap())?.let { return it }
        }
        return null
    }

    private fun activityOf(host: Any): Activity? = when (host) {
        is Activity -> host
        else -> (Reflect.call(host, "getActivity") ?: Reflect.call(host, "requireActivity")) as? Activity
    }

    @Suppress("DEPRECATION") // typed Bundle getters cannot read an unknown host extra shape
    private fun macFromIntent(intent: Intent, managed: Set<String>): String? {
        normalizeIfManaged(intent.getStringExtra(EXTRA_DEVICE_MAC), managed)?.let { return it }
        normalizeIfManaged(intent.dataString, managed)?.let { return it }
        runCatching {
            (intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice)?.address
        }.getOrNull()?.let { address -> normalizeIfManaged(address, managed)?.let { return it } }
        intent.extras?.let { extras ->
            for (key in extras.keySet()) {
                normalizeIfManaged(key, managed)?.let { return it }
                findManagedMac(extras.get(key), MAX_MAC_SCAN_DEPTH, managed, IdentityHashMap())?.let { return it }
            }
        }
        return null
    }

    /**
     * Bounded reflection scan: a MAC-shaped string (or a `BluetoothDevice` address) reachable from
     * [value], kept only when it is one of the managed MACs. Recursion is gated on host-package types so
     * the scan never walks into the framework object graph.
     */
    @Suppress("DEPRECATION") // a host Bundle's value types are unknown at compile time
    private fun findManagedMac(
        value: Any?,
        depth: Int,
        managed: Set<String>,
        seen: IdentityHashMap<Any, Boolean>,
    ): String? {
        if (value == null) return null
        macOf(value)?.let { candidate -> if (candidate in managed) return candidate }
        if (depth <= 0 || seen.containsKey(value) || isScanTerminal(value)) return null
        seen[value] = true
        val next = depth - 1
        when (value) {
            is Intent -> return macFromIntent(value, managed)
            is Bundle -> for (key in value.keySet()) {
                normalizeIfManaged(key, managed)?.let { return it }
                findManagedMac(value.get(key), next, managed, seen)?.let { return it }
            }

            is Iterable<*> -> for (item in value) findManagedMac(item, next, managed, seen)?.let { return it }
            is Map<*, *> -> for ((key, item) in value) {
                findManagedMac(key, next, managed, seen)?.let { return it }
                findManagedMac(item, next, managed, seen)?.let { return it }
            }
        }
        if (!isHostPackage(value.javaClass)) return null
        for (owner in Reflect.hierarchyOf(value.javaClass)) {
            for (field in runCatching { owner.declaredFields }.getOrNull().orEmpty()) {
                if (isSkippedField(field)) continue
                val child = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull() ?: continue
                findManagedMac(child, next, managed, seen)?.let { return it }
            }
        }
        return null
    }

    /** The MAC-shaped view of one object: a `String` field/value, or a `BluetoothDevice`'s address. */
    private fun macOf(value: Any): String? = when (value) {
        is String -> normalizeMac(value)
        is BluetoothDevice -> runCatching { value.address }.getOrNull()?.let(::normalizeMac)
        else -> Reflect.readStringFieldWhere(value, MelodyMac::isMacAddress)?.let(::normalizeMac)
    }

    private fun normalizeMac(raw: String?): String? {
        val token = raw?.trim().orEmpty()
        if (!MelodyMac.isMacAddress(token)) return null
        return MelodyMac.normalize(token)
    }

    private fun normalizeIfManaged(raw: String?, managed: Set<String>): String? =
        normalizeMac(raw)?.takeIf { it in managed }

    private fun isSkippedField(field: Field): Boolean =
        field.isSynthetic || Modifier.isStatic(field.modifiers) || field.type.isPrimitive

    private fun isScanTerminal(value: Any): Boolean {
        val name = value.javaClass.name
        return name.startsWith("android.view.") ||
            name.startsWith("android.widget.") ||
            name.startsWith("android.graphics.") ||
            name.startsWith("android.content.res.") ||
            value is ClassLoader ||
            value is Class<*>
    }

    private fun isHostPackage(type: Class<*>): Boolean =
        type.name.startsWith("com.oplus.") || type.name.startsWith("com.coui.")

    // --- host classification ----------------------------------------------------------------------

    private fun isRelevantActivity(className: String): Boolean = className in ACTIVITY_CLASSES

    private fun isRelevantHost(host: Any): Boolean {
        if (host is Activity) return isRelevantActivity(host.javaClass.name)
        val activity = Reflect.call(host, "getActivity") ?: Reflect.call(host, "requireActivity")
        val activityName = activity?.javaClass?.name
        if (activityName != null && isRelevantActivity(activityName)) return true
        return host.javaClass.name.startsWith("com.oplus.melody.onespace.")
    }

    private fun screenLabel(host: Any): String {
        val activityName = when (host) {
            is Activity -> host.javaClass.name
            else -> (Reflect.call(host, "getActivity") ?: Reflect.call(host, "requireActivity"))?.javaClass?.name
        }
        return activityName?.substringAfterLast('.') ?: host.javaClass.simpleName
    }

    private companion object {
        val ACTIVITY_CLASSES = listOf(
            "com.oplus.melody.ui.component.detail.DetailMainActivity",
            "com.oplus.melody.onespace.OneSpaceDetailActivity",
        )

        /** Same probing list M1 uses; a renamed base is covered by the Activity lifecycle scan. */
        val FRAGMENT_CLASSES = listOf(
            "com.oplus.melody.ui.base.c",
            "com.oplus.melody.ui.base.b",
            "com.coui.appcompat.preference.h",
            "androidx.preference.g",
        )

        const val EXTRA_DEVICE_MAC = "device_mac_info"
        const val FIRST_DELAY_MS = 300L

        /** `A9.f`, the R8 synthetic LiveData observer that materialises the detail group model. */
        const val GROUP_OBSERVER_CLASS = "A9.f"

        /** Synthetic capture slot of [GROUP_OBSERVER_CLASS] (the observing fragment). */
        const val CAPTURE_FIELD = "b"

        /** Re-apply cadence while a relevant page is resumed; cheap (top-level rows only). */
        const val POLL_INTERVAL_MS = 1000L

        /** Fast-phase ticks (~2 minutes at 1 Hz): far past the host's asynchronous fill. */
        const val MAX_POLL_TICKS = 120

        /** After the fast phase the panel only needs a cheap self-heal cadence while it stays visible. */
        const val IDLE_POLL_INTERVAL_MS = 5_000L

        /** One unconditional row dump every 10 ticks (~10 s) while polling, for on-device diagnosis. */
        const val HEARTBEAT_TICKS = 10

        /** Coalescing window for snapshot-driven re-applies (M4.4); a burst of pushes costs one walk. */
        const val SNAPSHOT_DEBOUNCE_MS = 250L

        /** Bounded fallback scan when the Intent carries no MAC; the page's device is 1~2 hops away. */
        const val MAX_MAC_SCAN_DEPTH = 3
    }
}

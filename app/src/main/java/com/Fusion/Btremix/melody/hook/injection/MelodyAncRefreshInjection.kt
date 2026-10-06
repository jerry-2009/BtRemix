package com.Fusion.Btremix.melody.hook.injection

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.Fusion.Btremix.melody.api.MelodyMac
import com.Fusion.Btremix.melody.hook.MelodyLog
import com.Fusion.Btremix.melody.hook.MelodyAnchorSession
import com.Fusion.Btremix.melody.hook.Reflect
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClient
import com.Fusion.Btremix.melody.hook.bridge.MelodyBridgeClients
import com.Fusion.Btremix.melody.hook.anchor.MelodyAnchorCatalog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M5.1 follow-up: re-dispatch the host's own ANC view-model after a redirected write.
 *
 * The host builds its native ANC surfaces from a `Ba/z` noise-reduction VO that is produced by a
 * `LiveData` mapping of its own `EarphoneDTO` (`A0/q.apply` -> `new Ba/z(dto)` -> `A9/K.onChanged` ->
 * `NoiseReductionItem.onEarphoneDataChanged`). That LiveData is fed by the host's own btsdk session -
 * which M3.4 suppressed and M5 redirects away - so after a click the page keeps its stale VO and never
 * re-reads our projected `getNoiseReductionModeIndex`. Re-opening the page fixes it, which is exactly
 * the "click works, shown state does not move" report.
 *
 * This hook closes the loop with the host's own refresh entry points:
 *  - the live `NoiseReductionItem` (detail page) is re-dispatched with its own VO whose
 *    `mCurrentNoiseReductionModeIndex` is set to the projected index;
 *  - the live `OneSpaceListFragment` gets the same treatment through its `F` VO field + `z()`
 *    (`updateNoiseMenuCheckState`), which is also where the `mNoiseReductionVO is null!` log came from.
 *
 * It is driven by the bridge's snapshot pushes (the same ones the M4.4 panel already listens to), so it
 * runs in whichever host process owns the page. Everything is fail-open: no bridge, no managed MAC, no
 * live surface or an unwritable VO simply does nothing.
 */
internal class MelodyAncRefreshInjection(
    private val module: XposedInterface,
    private val log: MelodyLog,
    private val loader: ClassLoader,
    /**
     * Extra re-publish step for the device-centre card row, which lives in the SDK repository rather than
     * in the LiveData the rest of this class drives ([MelodyAncCardMenuInjection.republish]).
     */
    private val onProjectionChanged: ((String) -> Unit)? = null,
) {

    private val handler = Handler(Looper.getMainLooper())

    /** Live detail-page ANC items and OneSpace fragments (weak, pruned on iteration). */
    private val detailItems = Collections.synchronizedList(ArrayList<WeakReference<Any>>())
    private val oneSpaceFragments = Collections.synchronizedList(ArrayList<WeakReference<Any>>())

    /** One coalesced refresh is pending; set on the binder thread, cleared on main. */
    private val pending = AtomicBoolean(false)

    @Volatile
    private var listenerRegistered = false

    /** Last projected index per MAC: a push that does not change it is not worth any UI work. */
    private val lastIndexByMac = ConcurrentHashMap<String, Int>()

    /** Resolved `LiveData` setters per concrete class (`emptyList` = looked up, nothing matched). */
    private val cachedSetters = ConcurrentHashMap<Class<*>, List<Method>>()

    /** Last copy-skip reason per MAC, so the fall-back to the same instance is logged once. */
    private val lastCopySkip = ConcurrentHashMap<String, String>()

    /** Stamped copies sent during the current [refresh] pass (main thread only). */
    private var stampedThisPass = 0

    @Volatile
    private var dtoClassResolved = false
    private var resolvedDtoClass: Class<*>? = null

    private var voClass: Class<*>? = null
    private var armAttempts = 0

    fun install() {
        voClass = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.ANC_REFRESH_VO, loader)
        val detail = hookDetailItem()
        val space = hookOneSpace()
        // The card model is built in the main process where neither of the two surfaces above exists, so
        // arming the snapshot listener must not depend on them: every host process participates.
        hookCardProvider()
        arm()
        if (voClass == null) {
            log.event("melody.anchor.missing", "hook" to HOOK, "class" to VO_CLASS)
        }
        if (!detail && !space) {
            log.event("melody.anchor.missing", "hook" to HOOK)
        }
    }

    // --- anchors --------------------------------------------------------------------------------

    private fun hookDetailItem(): Boolean {
        val cls = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.ANC_REFRESH_ITEM, loader) ?: return false
        val vo = voClass ?: return false
        val method = Reflect.findMethod(cls, ITEM_METHOD, arrayOf(vo))
            ?: Reflect.findUniqueMethodByParams(cls, arrayOf(vo))
            ?: return false
        module.hook(method).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            // The host calls this once per data dispatch, which is exactly when the item becomes live.
            runCatching {
                remember(detailItems, chain.thisObject)
                // First tracked surface also arms the snapshot listener (and corrects the index in case
                // the page was built from a stale VO).
                scheduleRefresh()
            }
            result
        })
        Reflect.findMethod(cls, "onDetached", emptyArray())?.let { detach ->
            module.hook(detach).intercept(XposedInterface.Hooker { chain ->
                runCatching { forget(detailItems, chain.thisObject) }
                chain.proceed()
            })
        }
        log.event("melody.anchor.hooked", "hook" to HOOK, "target" to "detail", "class" to cls.name, "method" to method.name)
        return true
    }

    private fun hookOneSpace(): Boolean {
        val cls = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.ANC_REFRESH_ONE_SPACE, loader) ?: return false
        val update = Reflect.findMethod(cls, ONE_SPACE_UPDATE, emptyArray()) ?: return false
        module.hook(update).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            runCatching {
                remember(oneSpaceFragments, chain.thisObject)
                scheduleRefresh()
            }
            result
        })
        Reflect.findMethod(cls, "onPause", emptyArray())?.let { pause ->
            module.hook(pause).intercept(XposedInterface.Hooker { chain ->
                runCatching { forget(oneSpaceFragments, chain.thisObject) }
                chain.proceed()
            })
        }
        log.event("melody.anchor.hooked", "hook" to HOOK, "target" to "onespace", "class" to cls.name, "method" to update.name)
        return true
    }

    /**
     * The COUI card framework asks the provider for its per-widget LiveData in `onCardsObserve`; hooking
     * it is only an extra chance to arm this process's snapshot listener (a card that is already on the
     * desktop may not re-observe). The refresh itself stays snapshot-driven.
     */
    private fun hookCardProvider(): Boolean {
        val cls = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.CARD_WIDGET_PROVIDER, loader) ?: return false
        var hooked = false
        for ((name, params) in listOf(
            "onCardsObserve" to arrayOf(Context::class.java, java.util.List::class.java),
            "onCardCreate" to arrayOf(Context::class.java, String::class.java),
        )) {
            val method = Reflect.findMethod(cls, name, params) ?: continue
            module.hook(method).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                runCatching {
                    log.event("melody.card.observe", "target" to name, "class" to cls.name)
                    arm()
                }
                result
            })
            hooked = true
        }
        if (hooked) log.event("melody.anchor.hooked", "hook" to HOOK, "target" to "card", "class" to cls.name)
        return hooked
    }

    /**
     * Registers the snapshot listener as soon as this process has a bridge client. The client appears
     * when the bridge installer starts (a doorbell may arrive later), so a short retry window is used;
     * once armed nothing else in this class depends on a UI surface existing.
     */
    private fun arm() {
        val client = MelodyBridgeClients.existing()
        if (client != null) {
            ensureListener(client)
            return
        }
        if (armAttempts >= MAX_ARM_ATTEMPTS) return
        armAttempts++
        handler.postDelayed({ runCatching { arm() } }, ARM_RETRY_MS)
    }

    // --- refresh --------------------------------------------------------------------------------

    private fun scheduleRefresh() {
        if (!pending.compareAndSet(false, true)) return
        handler.postDelayed({
            pending.set(false)
            runCatching { refresh() }.onFailure { log.warn("melody.anc.refresh_failed", it) }
        }, DEBOUNCE_MS)
    }

    private fun ensureListener(client: MelodyBridgeClient) {
        if (listenerRegistered) return
        listenerRegistered = true
        client.addSnapshotListener { scheduleRefresh() }
        log.event("melody.anchor.hooked", "hook" to HOOK, "target" to "snapshot_listener")
        // Also under the always-on `melody.anc.refresh.*` prefix: without the listener the whole refresh
        // chain (and therefore the device-centre card re-publish) never runs, and that has to be visible
        // even when the host's diagnostics switch reads stale.
        log.event("melody.anc.refresh.armed", "pid" to android.os.Process.myPid())
    }

    /** Runs on the main thread; re-emits the host's own DTO LiveData and nudges each live surface. */
    private fun refresh() {
        val client = MelodyBridgeClients.existing() ?: return
        ensureListener(client)
        stampedThisPass = 0
        val managed = runCatching { client.managedMacsFast() }.getOrDefault(emptyList())
        if (managed.isEmpty()) return
        // One line per pass: "the host received a push and reached the refresh" is otherwise
        // indistinguishable from "the push never arrived", and the card row only moves through here.
        log.event("melody.anc.refresh.pass", "managed" to managed.size)
        val managedSet = managed.map(MelodyMac::normalize).toSet()
        var activeReposted = false
        for (mac in managedSet) {
            val index = client.currentAncIndexFast(mac) ?: continue
            if (lastIndexByMac.put(mac, index) == index) continue
            // The main process has no ANC preference of its own, but the card model maps this DTO
            // LiveData, so the re-emit must happen there too - not only where a page is resumed.
            val perMac = reemitEarphoneLiveData(mac, index)
            // The desktop card (`MelodyCardDataSender` -> `n7/f`) and the health modules observe the
            // *active earphone* LiveData `p()`, which is not keyed by MAC and therefore has no
            // `A/L/x(mac)` counterpart. Re-post it once per pass, on the first MAC that moved.
            val active = if (activeReposted) {
                0
            } else {
                reemitActiveEarphoneLiveData(mac, index).also { if (it > 0) activeReposted = true }
            }
            var changed = 0
            for (reference in snapshot(detailItems)) {
                val item = reference.get() ?: continue
                if (macFor(item, managedSet) != mac) continue
                if (refreshDetailItem(item, index)) changed++
            }
            for (reference in snapshot(oneSpaceFragments)) {
                val fragment = reference.get() ?: continue
                if (macFor(fragment, managedSet) != mac) continue
                if (refreshOneSpace(fragment, mac, index)) changed++
            }
            // The device-centre card's row is stored in the SDK repository; replay the host's own
            // "restore + publish" path so it carries the projected index instead of the empty one.
            runCatching { onProjectionChanged?.invoke(mac) }
            log.event(
                "melody.anc.refreshed",
                "mac" to mac,
                "index" to index,
                "changed" to changed,
                // Which LiveData actually accepted a re-post. `postValue`/`setValue` are *protected*
                // on `LiveData`, so a `Class.getMethod` lookup on them always misses; when both are
                // zero the UI can only be stale no matter how many surfaces are registered.
                "reemit" to perMac,
                "active" to active,
                // How many of those were sent as a *stamped copy* rather than the same instance: without
                // the copy the host's own dedupe swallows the re-post (see [projectedCopy]).
                "stamped" to stampedThisPass,
            )
        }
    }

    /**
     * Re-posts the repository's per-MAC `EarphoneDTO` LiveData so the host's own observers rebuild every
     * derived view-model (`Ba/z` for the detail page and OneSpace, and whatever the card model maps from
     * the DTO). That is the host's normal "device data changed" path - the piece M3.4/M5 removed by
     * suppressing the official session. The value re-posted is the host's own current object, so the
     * only thing that changes is that our hooked getters are read again.
     */
    private fun reemitEarphoneLiveData(mac: String, index: Int): Int {
        val repoClass = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.EARPHONE_REPOSITORY, loader) ?: return 0
        val repo = repoInstance(repoClass) ?: return 0
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var posted = 0
        for (name in LIVE_DATA_METHODS) {
            val method = Reflect.findMethod(repoClass, name, arrayOf(String::class.java)) ?: continue
            val live = runCatching {
                method.isAccessible = true
                method.invoke(repo, mac)
            }.getOrNull() ?: continue
            if (repost(live, seen, mac, index)) posted++
        }
        return posted
    }

    /**
     * Re-posts the repository's **active earphone** LiveData (`p()`): the desktop card's data sender and
     * several health modules observe it instead of a per-MAC getter, so without this re-post the card
     * keeps the DTO it received before the redirected write.
     */
    private fun reemitActiveEarphoneLiveData(mac: String, index: Int): Int {
        val repoClass = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.EARPHONE_REPOSITORY, loader) ?: return 0
        val repo = repoInstance(repoClass) ?: return 0
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var posted = 0
        for (name in ACTIVE_LIVE_DATA_METHODS) {
            val method = Reflect.findNoArgMethod(repoClass, name) ?: continue
            val live = runCatching {
                method.isAccessible = true
                method.invoke(repo)
            }.getOrNull() ?: continue
            if (repost(live, seen, mac, index)) posted++
        }
        return posted
    }

    /**
     * Puts the LiveData's own current value back through its setter, so every observer re-runs its map
     * and re-reads our hooked DTO getters. Returns whether the post was issued.
     *
     * The host's `androidx.lifecycle` is R8-processed, so **no name survives**: on 17.6.3
     * `LiveData.getValue` is `d()`, `setValue` is `l()` and `postValue` is `j()`. Name lookups (and
     * `Class.getMethod`, which also misses protected methods) therefore always fail, which is what made
     * the first two versions of this re-emit silently post nothing (`melody.anc.refreshed reemit=0`).
     * Everything here is resolved by shape instead: the value by the most-derived no-arg `()Object`
     * method, the two setters by the fingerprint of the obfuscated `LiveData` class itself (exactly two
     * `(Object) -> void` methods declared next to a `() -> Object` getter). Reflection-only: the module
     * cannot compile against `androidx.lifecycle`.
     */
    private fun repost(live: Any, seen: MutableSet<Any>, mac: String, index: Int): Boolean {
        if (!seen.add(live)) return false
        val value = currentValue(live) ?: return false
        val emitted = projectedCopy(value, mac, index) ?: value
        val setters = settersOf(live.javaClass) ?: return false
        var posted = false
        for (setter in setters) {
            if (runCatching { setter.invoke(live, emitted) }.isSuccess) posted = true
        }
        if (emitted !== value) stampedThisPass++
        return posted
    }

    /**
     * A **copy** of [value] whose real `noiseReductionModeIndex` field carries [index].
     *
     * Re-posting the LiveData's own current object is not enough: the host guards its streams against
     * duplicates (`r7/e.b` = `Transformations.distinctUntilChanged`, and `y8/k.m(T)` returns `false`
     * when the new value is the same instance), and `EarphoneDTO.equals` is a field-hash compare
     * (`common.data.a` / BaseBean). With identical fields the emission is swallowed and no observer
     * rebuilds, which is exactly the "`reemit=1` but nothing repaints" symptom.
     *
     * `noiseReductionModeIndex` is the same value our hooked getter already projects, so stamping it
     * keeps the object self-consistent (and any host code that reads the field directly sees the same
     * answer as the getter). The copy is only made for the MAC that actually moved; fail-open returns
     * `null` and the caller posts the original instance.
     */
    private fun projectedCopy(value: Any?, mac: String, index: Int): Any? {
        if (value == null) return null
        val cls = dtoClass ?: return null
        if (!cls.isInstance(value)) return null
        val valueMac = Reflect.callString(value, "getMacAddress")?.let(MelodyMac::normalize)
        if (valueMac != mac) return null
        val copy = Reflect.call(value, "clone") ?: return null
        if (copy === value) return null
        if (!Reflect.writeField(copy, arrayOf(FIELD_NOISE_INDEX), index)) {
            noteCopySkip(mac, "field")
            return null
        }
        return copy
    }

    /** One line per MAC when a stamped copy could not be built, so a silent fallback is visible. */
    private fun noteCopySkip(mac: String, reason: String) {
        if (lastCopySkip.put(mac, reason) == reason) return
        log.event("melody.anc.reemit.skip", "mac" to mac, "reason" to reason)
    }

    /** `LiveData.getValue()` under a renamed host build: the most-derived no-arg `Object` getter. */
    private fun currentValue(live: Any): Any? {
        for (owner in Reflect.hierarchyOf(live.javaClass)) {
            val getter = runCatching { owner.declaredMethods }.getOrNull().orEmpty().firstOrNull {
                !it.isSynthetic && it.parameterTypes.isEmpty() && it.returnType == Any::class.java
            } ?: continue
            return runCatching {
                getter.isAccessible = true
                getter.invoke(live)
            }.getOrNull()
        }
        return null
    }

    /** The host DTO class, resolved once: only its instances may be copied and stamped. */
    private val dtoClass: Class<*>?
        get() {
            if (!dtoClassResolved) {
                resolvedDtoClass = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.DTO_EARPHONE, loader)
                dtoClassResolved = true
            }
            return resolvedDtoClass
        }

    /**
     * `LiveData.setValue(T)` / `postValue(T)` under a renamed host build: the two `(Object) -> void`
     * methods declared by the same class that also declares the `() -> Object` getter. Both are valid
     * re-emits on the main thread (`setValue` synchronously, `postValue` on the next loop), so calling
     * both is safe and keeps this independent of which obfuscated name is which. Cached per class.
     */
    private fun settersOf(type: Class<*>): List<Method>? {
        cachedSetters[type]?.let { return it.ifEmpty { null } }
        var found: List<Method>? = null
        for (owner in Reflect.hierarchyOf(type)) {
            val declared = runCatching { owner.declaredMethods }.getOrNull().orEmpty()
            val setters = declared.filter {
                !it.isSynthetic && !it.isBridge &&
                    it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Any::class.java
            }
            if (setters.size != 2) continue
            if (declared.none { !it.isSynthetic && it.parameterTypes.isEmpty() && it.returnType == Any::class.java }) continue
            runCatching { setters.forEach { setter -> setter.isAccessible = true } }
            found = setters
            break
        }
        cachedSetters[type] = found ?: emptyList()
        return found
    }

    private fun repoInstance(repoClass: Class<*>): Any? = runCatching {
        val instance = Reflect.findNoArgMethod(repoClass, REPO_INSTANCE) ?: return null
        instance.isAccessible = true
        instance.invoke(null)
    }.getOrNull()

    /**
     * `NoiseReductionItem.onEarphoneDataChanged` stores the VO and re-runs `updateActionView` plus every
     * child item's `c(vo)`, so mutating the VO's index and re-dispatching is the host's own refresh path.
     */
    private fun refreshDetailItem(item: Any, index: Int): Boolean {
        val vo = Reflect.readField(item, VO_FIELD_ITEM) ?: return false
        if (Reflect.readField(vo, VO_FIELD_INDEX) as? Int == index) return false
        if (!Reflect.writeField(vo, arrayOf(VO_FIELD_INDEX), index)) return false
        Reflect.invokeSingleArg(item, ITEM_METHOD, vo)
        return true
    }

    /** OneSpace keeps its VO in field `F`; `z()` is `updateNoiseMenuCheckState`. */
    private fun refreshOneSpace(fragment: Any, mac: String, index: Int): Boolean {
        val existing = Reflect.readField(fragment, VO_FIELD_ONE_SPACE)
        val vo = existing ?: buildVo(mac) ?: run {
            log.event("melody.anc.refresh.onespace", "mac" to mac, "index" to index, "vo" to false)
            return false
        }
        val previous = Reflect.readField(vo, VO_FIELD_INDEX) as? Int
        if (previous == index && existing != null) return false
        if (!Reflect.writeField(vo, arrayOf(VO_FIELD_INDEX), index)) return false
        Reflect.writeField(fragment, arrayOf(VO_FIELD_ONE_SPACE), vo)
        // `z()` silently returns when the parent `G` (or its `childrenMode`) is missing, in which case
        // the row would never move; report the pieces so a real device run explains the no-op.
        val parent = Reflect.readField(fragment, VO_FIELD_ONE_SPACE_PARENT)
        val children = parent?.let { Reflect.call(it, "getChildrenMode") } as? List<*>
        log.event(
            "melody.anc.refresh.onespace",
            "mac" to mac,
            "index" to index,
            "previous" to previous,
            "built" to (existing == null),
            "menu" to (Reflect.readField(fragment, VO_FIELD_ONE_SPACE_MENU) != null),
            "parent" to (parent != null),
            "children" to (children?.size ?: -1),
        )
        val update = Reflect.findNoArgMethod(fragment.javaClass, ONE_SPACE_UPDATE) ?: return false
        return runCatching {
            update.isAccessible = true
            update.invoke(fragment)
        }.isSuccess
    }

    /** Builds a `Ba/z` from the repository's current `EarphoneDTO` (its getters carry our projection). */
    private fun buildVo(mac: String): Any? {
        val cls = voClass ?: return null
        val repoClass = MelodyAnchorSession.classOrNull(MelodyAnchorCatalog.EARPHONE_REPOSITORY, loader) ?: return null
        val repo = repoInstance(repoClass) ?: return null
        val dto = callStringArgReturning(repo, DTO_CLASS, mac) ?: return null
        return Reflect.newInstanceArgs(cls, dto.javaClass to dto)
    }

    private fun callStringArgReturning(target: Any, returnClassName: String, arg: String): Any? {
        val expected = Reflect.loadClass(returnClassName, loader) ?: return null
        for (owner in Reflect.hierarchyOf(target.javaClass)) {
            for (method in runCatching { owner.declaredMethods }.getOrNull().orEmpty()) {
                if (method.parameterTypes.size != 1 || method.parameterTypes[0] != String::class.java) continue
                if (!expected.isAssignableFrom(method.returnType)) continue
                val result = runCatching {
                    method.isAccessible = true
                    method.invoke(target, arg)
                }.getOrNull() ?: continue
                return result
            }
        }
        return null
    }

    /**
     * Which managed device a surface belongs to: a single managed device is used directly, otherwise a
     * bounded field read tries to find the managed MAC on the surface itself (the M4.3b rule: an
     * ambiguous surface is skipped rather than guessed).
     */
    private fun macFor(host: Any, managed: Set<String>): String? {
        if (managed.size == 1) return managed.first()
        return Reflect.readStringFieldWhere(host) { raw -> MelodyMac.normalize(raw) in managed }
            ?.let(MelodyMac::normalize)
    }

    // --- registry -------------------------------------------------------------------------------

    private fun remember(registry: MutableList<WeakReference<Any>>, host: Any?) {
        if (host == null) return
        synchronized(registry) {
            registry.removeAll { it.get() == null }
            if (registry.none { it.get() === host }) registry.add(WeakReference(host))
        }
    }

    private fun forget(registry: MutableList<WeakReference<Any>>, host: Any?) {
        if (host == null) return
        synchronized(registry) { registry.removeAll { it.get() == null || it.get() === host } }
    }

    private fun snapshot(registry: MutableList<WeakReference<Any>>): List<WeakReference<Any>> =
        synchronized(registry) { registry.toList() }

    private companion object {
        const val HOOK = "inject.anc_refresh"
        const val ITEM_CLASS = "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem"
        const val ITEM_METHOD = "onEarphoneDataChanged"
        const val ONE_SPACE_CLASS = "com.oplus.melody.onespace.b"
        const val ONE_SPACE_UPDATE = "z"
        const val VO_CLASS = "Ba.z"
        const val VO_FIELD_ITEM = "mNoiseReductionVO"
        const val VO_FIELD_ONE_SPACE = "F"
        const val VO_FIELD_ONE_SPACE_PARENT = "G"
        const val VO_FIELD_ONE_SPACE_MENU = "w"
        const val VO_FIELD_INDEX = "mCurrentNoiseReductionModeIndex"
        const val REPO_CLASS = "com.oplus.melody.model.repository.earphone.b"
        const val REPO_INSTANCE = "I"
        const val DTO_CLASS = "com.oplus.melody.model.repository.earphone.EarphoneDTO"
        /** The DTO's own real noise field; [projectedCopy] stamps it so the host's dedupe lets the post through. */
        const val FIELD_NOISE_INDEX = "noiseReductionModeIndex"
        const val CARD_PROVIDER_CLASS = "com.oplus.melody.card.MelodyCardWidgetProvider"

        /** Per-MAC `LiveData` getters recorded for the repository's earphone DTO (17.6.3). */
        val LIVE_DATA_METHODS = listOf("A", "L", "x")

        /**
         * No-argument `LiveData` getters recorded for the *active* earphone (17.6.3): `p()` is what
         * `MelodyCardDataSender` (`n7/e$a`) maps into the desktop card's `n7/f`, and what the health
         * modules and `EarphoneControlProvider` observe. Kept to the one accessor with hard evidence -
         * re-posting an unrelated "command result" LiveData could re-trigger its consumers.
         */
        val ACTIVE_LIVE_DATA_METHODS = listOf("p")

        /** Coalesces a burst of pushes (the host pushes several per write) into one main-thread pass. */
        const val DEBOUNCE_MS = 200L

        /** Listener arming retries while the bridge client is still being created. */
        const val ARM_RETRY_MS = 1_500L
        const val MAX_ARM_ATTEMPTS = 80
    }
}
